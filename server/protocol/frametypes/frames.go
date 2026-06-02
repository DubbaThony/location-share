package frametypes

type FrameType uint8
type HandshakeStatusByte uint8
type CtrlType uint8
type FeatureFlags uint32

const (
	FrameTyHandshakeClient          = FrameType(1)
	FrameTyHandshakeServer          = FrameType(2)
	FrameTyEncryptedHandshakeClient = FrameType(3)
	FrameTyEncryptedHandshakeServer = FrameType(4)
	FrameTyPing                     = FrameType(5)
	FrameTyData                     = FrameType(6)
	FrameTyCtrl                     = FrameType(7)
)

const (
	HandshakeStatusOK     = HandshakeStatusByte(0)
	HandshakeProtoTooHigh = HandshakeStatusByte(254)
	HandshakeProtoTooLow  = HandshakeStatusByte(255)
)

const (
	CtrlTypeGetSubCount   = CtrlType(1)
	CtrlTypeFatalMsgError = CtrlType(252)
	CtrlTypeFatalError    = CtrlType(253)
	CtrlTypeBye           = CtrlType(254)
	CtrlTypeACK           = CtrlType(255)
)

const ProtoVersion = 2

type AbstractFrame struct {
	ConnID    uint64
	FrameType FrameType
	Body      []byte
	CRC32     uint32
}

type ClientHandshakeFrame struct {
	ECDH         [65]byte
	ProtoVersion uint8
}

type ServerHandshakeFrame struct {
	ECDH         [65]byte
	Signature    [64]byte
	Status       HandshakeStatusByte
	ConnectionID uint64
}

type EncryptedClientHandshakeFrame struct {
	FeatFlag FeatureFlags
}

type EncryptedServerHandshakeFrame struct {
	FeatFlag    FeatureFlags
	PublicAlias [32]byte
}

type PingFrame struct {
	Nonce uint16
}

type DataFrame struct {
	Data []byte
}

type CtrlFrame struct {
	CtrlType  CtrlType
	CtrlNonce uint16
	Extra     []byte
}
