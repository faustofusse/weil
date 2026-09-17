package main

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sync"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
	"google.golang.org/protobuf/proto"
)

type bridge struct {
	cfg       config
	container *sqlstore.Container
	log       waLog.Logger
	http      *http.Client

	mu     sync.RWMutex
	client *whatsmeow.Client
}

func newBridge(cfg config, container *sqlstore.Container, log waLog.Logger) *bridge {
	return &bridge{
		cfg:       cfg,
		container: container,
		log:       log,
		// The worker spends the whole call in Gemini before it hands back a
		// reply; a one-line message measured ~55 s on the slow model, so the
		// budget is generous on purpose.
		http: &http.Client{Timeout: 120 * time.Second},
	}
}

// start restores the paired device (if any) and connects. A bridge with no
// device still starts and serves HTTP: that is how /pair gets called.
func (b *bridge) start(ctx context.Context) error {
	device, err := b.container.GetFirstDevice(ctx)
	if err != nil {
		return fmt.Errorf("load device: %w", err)
	}
	return b.connect(ctx, device)
}

func (b *bridge) connect(ctx context.Context, device *store.Device) error {
	client := whatsmeow.NewClient(device, waLog.Stdout("whatsmeow", b.cfg.logLevel, true))
	client.AddEventHandler(b.handleEvent)
	b.mu.Lock()
	b.client = client
	b.mu.Unlock()
	if err := client.Connect(); err != nil {
		return err
	}
	if client.Store.ID == nil {
		b.log.Warnf("no device paired yet — POST /pair {\"phone\":\"...\"} to link this bridge")
	}
	return nil
}

func (b *bridge) stop() {
	if c := b.current(); c != nil {
		c.Disconnect()
	}
}

func (b *bridge) current() *whatsmeow.Client {
	b.mu.RLock()
	defer b.mu.RUnlock()
	return b.client
}

// ---------------------------------------------------------------- events

func (b *bridge) handleEvent(raw any) {
	switch evt := raw.(type) {
	case *events.Message:
		go b.onMessage(evt)
	case *events.Connected:
		b.log.Infof("connected as %s", b.jid())
	case *events.LoggedOut:
		// The companion device was unlinked (from the phone, or because the
		// primary stayed offline too long). Nothing to do but wait for /pair;
		// the stale keys would only produce decryption failures.
		b.log.Warnf("logged out (%s) — device row dropped, re-pair required", evt.Reason)
		if c := b.current(); c != nil && c.Store != nil {
			_ = c.Store.Delete(context.Background())
		}
	}
}

type inboundPayload struct {
	MessageID string `json:"message_id"`
	From      string `json:"from"`      // bare phone number, no @s.whatsapp.net
	PushName  string `json:"push_name"`
	Text      string `json:"text"`
	Timestamp int64  `json:"timestamp"` // epoch ms, like every other timestamp in Weil
}

type inboundReply struct {
	Reply string `json:"reply"`
}

func (b *bridge) onMessage(evt *events.Message) {
	// Only 1:1 text from other people. Groups, status broadcasts and our own
	// echoes are noise, and a bot that answers a group is a bot that gets the
	// number banned.
	//
	// A direct chat arrives addressed either by phone number or by LID
	// (WhatsApp's opaque per-user id, now the default for many senders), so
	// both servers count as 1:1 — matching only s.whatsapp.net silently drops
	// real messages.
	if evt.Info.IsGroup || evt.Info.IsFromMe ||
		(evt.Info.Chat.Server != types.DefaultUserServer && evt.Info.Chat.Server != types.HiddenUserServer) {
		return
	}
	// The worker keys links on the phone number; under LID addressing that is
	// the *alternative* address, not Sender.
	phone := evt.Info.Sender.User
	if evt.Info.Sender.Server == types.HiddenUserServer {
		if alt := evt.Info.SenderAlt; alt.Server == types.DefaultUserServer && alt.User != "" {
			phone = alt.User
		} else {
			b.log.Warnf("message from %s has no phone-number address, ignoring", evt.Info.Sender)
			return
		}
	}
	text := messageText(evt.Message)
	if text == "" {
		return
	}
	client := b.current()
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Second)
	defer cancel()

	// Read receipt + "typing" while the worker thinks: without it a 20 s
	// Gemini round trip looks like the bot is dead.
	_ = client.MarkRead(ctx, []types.MessageID{evt.Info.ID}, time.Now(), evt.Info.Chat, evt.Info.Sender)
	_ = client.SendChatPresence(ctx, evt.Info.Chat, types.ChatPresenceComposing, types.ChatPresenceMediaText)
	defer func() {
		_ = client.SendChatPresence(ctx, evt.Info.Chat, types.ChatPresencePaused, types.ChatPresenceMediaText)
	}()

	b.log.Infof("message from %s (%s): %q", phone, evt.Info.Chat, text)
	reply, err := b.notify(ctx, inboundPayload{
		MessageID: evt.Info.ID,
		From:      phone,
		PushName:  evt.Info.PushName,
		Text:      text,
		Timestamp: evt.Info.Timestamp.UnixMilli(),
	})
	if err != nil {
		b.log.Errorf("webhook failed for %s: %v", phone, err)
		// Silence is worse than an apology: the user has no other feedback.
		reply = "No pude procesar el mensaje. Probá de nuevo en un momento."
	}
	if reply == "" {
		return // the worker chose not to answer (unknown number, etc.)
	}
	if err := b.send(ctx, evt.Info.Chat, reply); err != nil {
		b.log.Errorf("send reply to %s: %v", phone, err)
	}
}

func messageText(msg *waE2E.Message) string {
	if msg == nil {
		return ""
	}
	if t := msg.GetConversation(); t != "" {
		return t
	}
	if t := msg.GetExtendedTextMessage().GetText(); t != "" {
		return t
	}
	// Caption on a shared photo/PDF — treated as plain text for now; the
	// document itself would go through /import/analyze instead.
	if t := msg.GetImageMessage().GetCaption(); t != "" {
		return t
	}
	return ""
}

// notify posts the message to the worker, signed so the endpoint can be public.
func (b *bridge) notify(ctx context.Context, payload inboundPayload) (string, error) {
	body, err := json.Marshal(payload)
	if err != nil {
		return "", err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, b.cfg.webhookURL, bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	req.Header.Set("content-type", "application/json")
	req.Header.Set("x-bridge-signature", sign(b.cfg.secret, body))
	res, err := b.http.Do(req)
	if err != nil {
		return "", err
	}
	defer res.Body.Close()
	if res.StatusCode >= 300 {
		return "", fmt.Errorf("webhook status %d", res.StatusCode)
	}
	var out inboundReply
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return "", fmt.Errorf("decode reply: %w", err)
	}
	return out.Reply, nil
}

func (b *bridge) send(ctx context.Context, to types.JID, text string) error {
	client := b.current()
	if client == nil || client.Store.ID == nil {
		return errors.New("not paired")
	}
	_, err := client.SendMessage(ctx, to, &waE2E.Message{Conversation: proto.String(text)})
	return err
}

func (b *bridge) jid() string {
	c := b.current()
	if c == nil || c.Store == nil || c.Store.ID == nil {
		return ""
	}
	return c.Store.ID.String()
}

func sign(secret string, body []byte) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(body)
	return "sha256=" + hex.EncodeToString(mac.Sum(nil))
}

func verify(secret, header string, body []byte) bool {
	return hmac.Equal([]byte(header), []byte(sign(secret, body)))
}
