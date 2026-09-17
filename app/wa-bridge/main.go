// wa-bridge pipes WhatsApp messages to the finance worker and back.
//
// It holds the one thing Cloudflare cannot host — a permanently open
// multi-device websocket with a live Signal session store — and nothing else.
// Every decision about what a message *means* is made by the worker.
package main

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	_ "github.com/tursodatabase/libsql-client-go/libsql"
	"go.mau.fi/whatsmeow/store/sqlstore"
	waLog "go.mau.fi/whatsmeow/util/log"
)

type config struct {
	tursoURL   string
	tursoToken string
	webhookURL string
	secret     string
	port       string
	logLevel   string
}

func loadConfig() (config, error) {
	c := config{
		tursoURL:   os.Getenv("TURSO_URL"),
		tursoToken: os.Getenv("TURSO_AUTH_TOKEN"),
		webhookURL: os.Getenv("WEBHOOK_URL"),
		secret:     os.Getenv("BRIDGE_SECRET"),
		port:       os.Getenv("PORT"),
		logLevel:   os.Getenv("LOG_LEVEL"),
	}
	if c.port == "" {
		c.port = "8080"
	}
	if c.logLevel == "" {
		c.logLevel = "INFO"
	}
	var missing []string
	for name, value := range map[string]string{
		"TURSO_URL":   c.tursoURL,
		"WEBHOOK_URL": c.webhookURL,
		"BRIDGE_SECRET": c.secret,
	} {
		if value == "" {
			missing = append(missing, name)
		}
	}
	if len(missing) > 0 {
		return c, fmt.Errorf("missing env: %s", strings.Join(missing, ", "))
	}
	return c, nil
}

// openStore points whatsmeow's session container at Turso.
//
// The dialect argument is dbutil's SQL *flavour* ("sqlite3"), not the driver
// name: libsql speaks SQLite, and it is the remote flavour that supports the
// interactive transactions sqlstore needs (D1's batch-only HTTP API does not).
func openStore(ctx context.Context, cfg config, log waLog.Logger) (*sqlstore.Container, error) {
	dsn := cfg.tursoURL
	if cfg.tursoToken != "" {
		sep := "?"
		if strings.Contains(dsn, "?") {
			sep = "&"
		}
		dsn += sep + "authToken=" + cfg.tursoToken
	}
	db, err := sql.Open("libsql", dsn)
	if err != nil {
		return nil, fmt.Errorf("open libsql: %w", err)
	}
	// More than one connection is required, not just nice to have: whatsmeow
	// reads a row set and writes inside the same call path (prekey upload,
	// app-state sync), so a single-connection pool deadlocks on the first
	// query issued while another result set is still open. Turso serialises
	// the writes server-side, so concurrency here costs nothing.
	db.SetMaxOpenConns(8)
	db.SetConnMaxIdleTime(30 * time.Second)
	if err := db.PingContext(ctx); err != nil {
		return nil, fmt.Errorf("ping turso: %w", err)
	}
	container := sqlstore.NewWithDB(db, "sqlite3", log)
	if err := container.Upgrade(ctx); err != nil {
		return nil, fmt.Errorf("upgrade session schema: %w", err)
	}
	return container, nil
}

func main() {
	cfg, err := loadConfig()
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	log := waLog.Stdout("bridge", cfg.logLevel, true)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	container, err := openStore(ctx, cfg, waLog.Stdout("store", cfg.logLevel, true))
	if err != nil {
		log.Errorf("%v", err)
		os.Exit(1)
	}

	bridge := newBridge(cfg, container, log)
	if err := bridge.start(ctx); err != nil {
		log.Errorf("connect: %v", err)
		os.Exit(1)
	}

	server := &http.Server{
		Addr:              ":" + cfg.port,
		Handler:           bridge.routes(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	go func() {
		log.Infof("listening on :%s", cfg.port)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Errorf("http: %v", err)
			stop()
		}
	}()

	<-ctx.Done()
	log.Infof("shutting down")
	shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = server.Shutdown(shutdown)
	bridge.stop()
}
