package cfg

import (
	"reflect"
	"strings"

	"github.com/DubbaThony/share-server/ifaces"
	"github.com/caarlos0/env/v11"
	"github.com/joho/godotenv"
)

type Fields struct {
	UDPPort                   uint16          `env:"UDP_PORT,required,notEmpty"`
	ApiListen                 string          `env:"API_LISTEN,required,notEmpty"`
	LogLevel                  ifaces.LogLevel `env:"LOG_LEVEL,required,notEmpty"`
	TraceInLogs               bool            `env:"TRACES_IN_LOGS" envDefault:"false"`
	MeteredSocket             bool            `env:"METERED_SOCKET" envDefault:"true"`
	MapLocation               string          `env:"MAP_LOCATION" envDefault:"map"`
	CompilanceEmailAddr       string          `env:"ADMIN_CONTACT"`
	DebugFaultySocket         bool            `env:"DEBUG_FFAULTY_SOCKET" envDefault:"false"`
	DebugFaultySocketRXPLPerc uint8           `env:"DEBUG_FAULTY_SOCKET_RX_PL_PERC" envDefault:"10"`
	DebugFaultySocketTXPLPerc uint8           `env:"DEBUG_FAULTY_SOCKET_TX_PL_PERC" envDefault:"10"`
}

type config struct {
	f Fields
}

func NewConfig() ifaces.Config {
	err := godotenv.Load(".env")
	if err != nil {
		panic(err)
	}
	c, err := env.ParseAsWithOptions[Fields](env.Options{
		RequiredIfNoDef: true,
		FuncMap: map[reflect.Type]env.ParserFunc{
			reflect.TypeOf(ifaces.LogLevel(0)): parseLogLevel,
		}})
	if err != nil {
		panic(err)
	}
	return &config{c}
}

func parseLogLevel(v string) (interface{}, error) {
	v = strings.ToLower(v)
	switch v {
	case "trace", "t":
		return ifaces.LogLevelTrace, nil
	case "debug", "d":
		return ifaces.LogLevelDebug, nil
	case "info", "i":
		return ifaces.LogLevelInfo, nil
	case "warn", "w":
		return ifaces.LogLevelWarn, nil
	case "error", "e", "err":
		return ifaces.LogLevelError, nil
	case "fatal", "f", "critical":
		return ifaces.LogLevelFatal, nil
	case "silent", "s", "mute", "none":
		return ifaces.LogLevelSilent, nil
	}
	return ifaces.LogLevelInfo, nil
}

func (c config) UDPPort() uint16 {
	return c.f.UDPPort
}

func (c config) ApiListen() string {
	return c.f.ApiListen
}

func (c config) LogLevel() ifaces.LogLevel {
	return c.f.LogLevel
}

func (c config) TraceInLogs() bool {
	return c.f.TraceInLogs
}

func (c config) MapLocation() string {
	return c.f.MapLocation
}

func (c config) ComplianceEmailAddr() string {
	return c.f.CompilanceEmailAddr
}

func (c config) MeteredSocket() bool {
	return c.f.MeteredSocket
}

func (c config) DebugFaultySocket() bool {
	return c.f.DebugFaultySocket
}

func (c config) DebugFaultySocketTXPLPerc() uint8 {
	return c.f.DebugFaultySocketTXPLPerc
}

func (c config) DebugFaultySocketRXPLPerc() uint8 {
	return c.f.DebugFaultySocketRXPLPerc
}
