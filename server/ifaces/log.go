package ifaces

type LogLevel uint8

const (
	LogLevelTrace  = LogLevel(0)
	LogLevelDebug  = LogLevel(1)
	LogLevelInfo   = LogLevel(2)
	LogLevelWarn   = LogLevel(3)
	LogLevelError  = LogLevel(4)
	LogLevelFatal  = LogLevel(5)
	LogLevelSilent = LogLevel(6)
)

type Config interface {
	UDPPort() uint16
	ApiListen() string
	LogLevel() LogLevel
	TraceInLogs() bool
	MapLocation() string
	ComplianceEmailAddr() string
	MeteredSocket() bool
}
