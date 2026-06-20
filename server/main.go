package main

import (
	"embed"
	"fmt"
	"os"

	"github.com/DubbaThony/share-server/api"
	"github.com/DubbaThony/share-server/cfg"
	"github.com/DubbaThony/share-server/identity"
	"github.com/DubbaThony/share-server/ifaces"
	"github.com/DubbaThony/share-server/protocol"
	"github.com/DubbaThony/share-server/publisher"
	"github.com/DubbaThony/share-server/sockets"
	. "github.com/DubbaThony/share-server/util"
	"github.com/labstack/echo/v4"
	"github.com/rs/zerolog"
)

const license = `
"THE BEERWARE LICENSE" (Revision 42):
DubbaThony wrote this code. As long as you retain this
notice, you can do whatever you want with this stuff. If we
meet someday, and you think this stuff is worth it, you can
buy me a beer in return.`

// Frontend assets are baked into the binary. They must live under server/
// (embed can't reach outside the module, e.g. ../web), and the static/ dir
// holds nothing but assets so it stays fully rm+cp replaceable.
//
//go:embed all:static
var staticFiles embed.FS

const (
	// dd.mm.YYYY HH:MM:SS+TZ. Translated to Go's reference-date format:
	// 02 = day, 01 = month, 2006 = year, 15 = hour (24h), 04 = minute,
	// 05 = second, -0700 = numeric TZ offset like +0200. Yes, this is
	// the canonical Go time-format scheme; no, it doesn't get less weird.
	logTimeFormat = "02.01.2006 15:04:05-0700"
)

func main() {
	fmt.Println(license)
	conf := cfg.NewConfig()
	l := logger(conf)
	ll := LComp(l, "main")
	id := identity.New(l)
	pub := publisher.New(l)
	as := api.New(conf, staticFiles, pub, id, LComp(l, "api"))

	sock, err := sockets.OpenUdp(LComp(l, "socket"), conf)
	if err != nil {
		ll.Fatal().Err(err).Msg("failed to open UDP socket")
		panic(fmt.Errorf("failed to open UDP socket: %w", err))
	}
	h := protocol.NewHandler(sock, LComp(l, "protocol"), pub, id)

	defer h.Close()
	e := echo.New()
	defer e.Close()
	as.InstallRoutes(e)
	err = e.Start(conf.ApiListen())
	if err != nil {
		l.Fatal().Err(err).Msg("failed to start HTTP server")
		panic(fmt.Errorf("failed to start HTTP server: %w", err))
	}
}

func logger(conf ifaces.Config) zerolog.Logger {
	var lvl zerolog.Level
	switch conf.LogLevel() {
	case ifaces.LogLevelTrace:
		lvl = zerolog.TraceLevel
	case ifaces.LogLevelDebug:
		lvl = zerolog.DebugLevel
	case ifaces.LogLevelInfo:
		lvl = zerolog.InfoLevel
	case ifaces.LogLevelWarn:
		lvl = zerolog.WarnLevel
	case ifaces.LogLevelError:
		lvl = zerolog.ErrorLevel
	case ifaces.LogLevelFatal:
		lvl = zerolog.FatalLevel
	case ifaces.LogLevelSilent:
		lvl = zerolog.NoLevel
	default:
		panic("invalid log level")
	}
	zerolog.TimeFieldFormat = logTimeFormat
	lp := zerolog.New(zerolog.ConsoleWriter{
		Out:        os.Stdout,
		TimeFormat: logTimeFormat,
	}).With()

	if conf.TraceInLogs() {
		lp = lp.Caller().CallerWithSkipFrameCount(3).CallerWithSkipFrameCount(4)
	}

	return lp.Timestamp().Logger().Level(lvl)
}
