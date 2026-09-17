package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/types"
)

func (b *bridge) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", b.handleHealth)
	mux.HandleFunc("POST /pair", b.handlePair)
	mux.HandleFunc("POST /logout", b.handleLogout)
	mux.HandleFunc("POST /send", b.handleSend)
	return mux
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("content-type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

// authed reads the body and checks the shared-secret signature. Returns nil
// (after answering 401) when the caller is not the worker.
func (b *bridge) authed(w http.ResponseWriter, r *http.Request) []byte {
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "unreadable body"})
		return nil
	}
	if !verify(b.cfg.secret, r.Header.Get("x-bridge-signature"), body) {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "bad signature"})
		return nil
	}
	return body
}

func (b *bridge) handleHealth(w http.ResponseWriter, r *http.Request) {
	client := b.current()
	writeJSON(w, http.StatusOK, map[string]any{
		"connected": client != nil && client.IsConnected(),
		"logged_in": client != nil && client.IsLoggedIn(),
		"jid":       b.jid(),
	})
}

type pairRequest struct {
	Phone string `json:"phone"` // international, digits only, no leading 0 or +
}

// handlePair links this bridge as a companion device of an existing WhatsApp
// account. Pairing codes are shown in the app under "Link with phone number",
// so no QR and no camera are involved.
func (b *bridge) handlePair(w http.ResponseWriter, r *http.Request) {
	body := b.authed(w, r)
	if body == nil {
		return
	}
	var req pairRequest
	if err := json.Unmarshal(body, &req); err != nil || req.Phone == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "phone required"})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()

	client := b.current()
	if client != nil && client.IsLoggedIn() {
		writeJSON(w, http.StatusConflict, map[string]string{"error": "already paired as " + b.jid()})
		return
	}
	// A fresh device row every attempt: a half-finished pairing leaves keys
	// that the server will reject on the next try.
	if err := b.connect(ctx, b.container.NewDevice()); err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": err.Error()})
		return
	}
	// PairPhone must run on an established login socket; whatsmeow's own docs
	// say a second after Connect is enough (the QR channel is the precise
	// signal, but we never show QRs).
	time.Sleep(2 * time.Second)

	code, err := b.current().PairPhone(ctx, req.Phone, true, whatsmeow.PairClientChrome, "Chrome (macOS)")
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": err.Error()})
		return
	}
	b.log.Infof("pairing code issued for %s", req.Phone)
	writeJSON(w, http.StatusOK, map[string]string{"code": code})
}

func (b *bridge) handleLogout(w http.ResponseWriter, r *http.Request) {
	if b.authed(w, r) == nil {
		return
	}
	client := b.current()
	if client == nil || client.Store.ID == nil {
		writeJSON(w, http.StatusOK, map[string]string{"status": "not paired"})
		return
	}
	if err := client.Logout(r.Context()); err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "logged out"})
}

type sendRequest struct {
	To   string `json:"to"` // bare number or full JID
	Text string `json:"text"`
}

// handleSend is for messages the worker starts on its own (link confirmations,
// reminders). Replies to an incoming message ride the webhook response instead.
func (b *bridge) handleSend(w http.ResponseWriter, r *http.Request) {
	body := b.authed(w, r)
	if body == nil {
		return
	}
	var req sendRequest
	if err := json.Unmarshal(body, &req); err != nil || req.To == "" || req.Text == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "to and text required"})
		return
	}
	jid, err := types.ParseJID(req.To)
	if err != nil || jid.User == "" {
		jid = types.NewJID(req.To, types.DefaultUserServer)
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	if err := b.send(ctx, jid, req.Text); err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "sent"})
}
