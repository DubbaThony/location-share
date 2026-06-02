package main

import (
	"archive/zip"
	"crypto/sha512"
	"embed"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"

	"github.com/DubbaThony/share-server/cfg"
	"github.com/DubbaThony/share-server/identity"
	"github.com/DubbaThony/share-server/ifaces"
	"github.com/DubbaThony/share-server/protocol"
	"github.com/DubbaThony/share-server/publisher"
	"github.com/DubbaThony/share-server/util"
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

// fixme: parametrization
const (
	// dd.mm.YYYY HH:MM:SS+TZ. Translated to Go's reference-date format:
	// 02 = day, 01 = month, 2006 = year, 15 = hour (24h), 04 = minute,
	// 05 = second, -0700 = numeric TZ offset like +0200. Yes, this is
	// the canonical Go time-format scheme; no, it doesn't get less weird.
	logTimeFormat = "02.01.2006 15:04:05-0700"
)

// tempting to create package for api, make it nice and neat and shit.
// Nah, dawg.

func main() {
	fmt.Println(license)
	conf := cfg.NewConfig()
	l := logger(conf)
	id := identity.New(l)
	e := echo.New()
	pub, hdl := publisher.New(l)
	rsock, err := net.ListenUDP("udp", &net.UDPAddr{
		IP:   net.ParseIP("0.0.0.0"),
		Port: int(conf.UDPPort()),
	})
	if err != nil {
		l.Fatal().Err(err).Msg("failed to open UDP socket")
		panic(fmt.Errorf("failed to open UDP socket: %w", err))
	}
	sock := ifaces.UDPSock(rsock)
	if conf.MeteredSocket() {
		sock = protocol.NewMeteredSocket(sock, l)
	}

	h := protocol.NewHandler(sock, l, pub, id)
	defer h.Close()
	defer e.Close()
	appH, appCrt := appHash(l)
	routeFrontend(e, conf, pub)
	e.GET("bind/:key", hdl)
	e.GET("identity", func(c echo.Context) error {
		return c.JSON(http.StatusOK, ifaces.BasicResponse[string]{
			Status: true,
			Result: fmt.Sprintf("0x%X", id.Identity()),
		})
	})
	e.GET("config", func(c echo.Context) error {
		return c.JSON(http.StatusOK, ifaces.BasicResponse[ifaces.AppConfig]{
			Status: true,
			Result: ifaces.AppConfig{
				Identity:              fmt.Sprintf("0x%X", id.Identity()),
				PrefferedAppBuildHash: appH,
				LocalSigner:           appCrt,
			},
		})
	})
	e.GET("gpdr-email", func(c echo.Context) error {
		return c.JSON(http.StatusOK, ifaces.BasicResponse[string]{
			Status: true,
			Result: conf.ComplianceEmailAddr(),
		})
	})
	err = e.Start(conf.ApiListen())
	if err != nil {
		l.Fatal().Err(err).Msg("failed to start HTTP server")
		panic(fmt.Errorf("failed to start HTTP server: %w", err))
	}
}

func routeFrontend(e *echo.Echo, conf ifaces.Config, pub ifaces.Publisher) {
	if fi, err := os.Stat(conf.MapLocation()); err != nil || !fi.IsDir() {
		panic("gps data directory missing or not a directory: " + conf.MapLocation())
	}
	staticRoot := echo.MustSubFS(staticFiles, "static")
	e.StaticFS("/static", staticRoot)
	serveIndex := echo.StaticFileHandler("index.html", staticRoot)
	serveView := echo.StaticFileHandler("view.html", staticRoot)
	serveExpired := echo.StaticFileHandler("expired.html", staticRoot)
	serve := func(c echo.Context) error {
		key := c.QueryParam("id")
		if key == "" {
			return serveIndex(c)
		} else if pub.Exists(key) {
			return serveView(c)
		} else {
			return serveExpired(c)
		}
	}
	e.GET("/", serve)
	e.GET("/index.html", serve)
	e.GET("/index.htm", serve)
	e.StaticFS("/map", os.DirFS(conf.MapLocation()))
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

func appHash(l zerolog.Logger) (appHash *string, signer *string) {
	f, err := staticFiles.Open("static/pl.dubba.share.apk")
	if err != nil {
		l.Warn().Err(err).Msg("Failed to open local embeded apk file. Was backend built correctly?")
		return nil, nil
	}
	h := sha512.New()
	_, err = io.Copy(h, f)
	if err != nil {
		l.Error().Err(err).Msg("WTF: cannot read embeded files?")
		return nil, nil
	}
	stat, err := f.Stat()
	if err != nil {
		l.Error().Err(err).Msg("WTF: cannot stat embeded files?")
		return nil, nil
	}
	ra, ok := f.(io.ReaderAt)
	if !ok {
		l.Error().Msg("WTF: embeded file isnt seekable")
	}

	r, err := zip.NewReader(ra, stat.Size())
	if err != nil {
		l.Error().Err(err).Msg("failed to open apk as zip")
		return nil, nil
	}
	certName := "META-INF/SHARE.RSA"
	for _, f := range r.File {
		if !strings.HasPrefix(f.Name, "META-INF/") {
			continue
		}
		upper := strings.ToUpper(f.Name)
		if strings.HasSuffix(upper, ".RSA") || strings.HasSuffix(upper, ".EC") || strings.HasSuffix(upper, ".DSA") {
			certName = f.Name
		}
	}
	fh, err := r.Open(certName)
	if err != nil {
		l.Error().Err(err).Msgf("failed to open %s cert", certName)
		return nil, nil
	}
	cert, err := io.ReadAll(fh)
	if err != nil {
		l.Error().Err(err).Msgf("failed to read %s cert", certName)
		return nil, nil
	}

	l.Info().Msg("embeded APK file exists")
	return util.P(hex.EncodeToString(h.Sum(nil))), util.P(hex.EncodeToString(cert))
}
