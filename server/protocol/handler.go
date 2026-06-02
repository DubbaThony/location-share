package protocol

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/rand"
	"crypto/sha512"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"time"

	"github.com/DubbaThony/share-server/ifaces"
	"github.com/DubbaThony/share-server/protocol/frametypes"
	"github.com/DubbaThony/share-server/protocol/marshaller"
	"github.com/DubbaThony/share-server/state"
	"github.com/DubbaThony/share-server/util"
	"github.com/rs/zerolog"
)

const udpLimit = 65507

type handler struct {
	sock  ifaces.UDPSock
	log   zerolog.Logger
	stop  *util.ChanDemux[struct{}]
	acker *acker
	pub   ifaces.Publisher
	id    ifaces.Identity
}

func NewHandler(sock ifaces.UDPSock, log zerolog.Logger, pub ifaces.Publisher, id ifaces.Identity) io.Closer {
	h := &handler{sock: sock, log: log, stop: util.NewChanDemux[struct{}](log), acker: newAcker(), pub: pub, id: id}
	go h.handle()
	go h.timeouter()
	go h.handleAckClrs()
	return h
}

func (h *handler) handleBlob(raw []byte, remote *net.UDPAddr) {
	abstractFrame, err := marshaller.New().UnmarshalAbstractFrame(raw)
	if err != nil {
		if errors.Is(err, ifaces.ErrPacketMalformed) {
			return
		}
		h.log.Error().Err(err).Msg("failed to unmarshal abstract frame")
		return
	}
	if !marshaller.New().ValidateFrameType(abstractFrame) {
		return
	}

	var ph ifaces.ConnPhase
	var crypt cipher.AEAD
	if abstractFrame.FrameType != frametypes.FrameTyHandshakeClient && !state.ConnMap.Update(abstractFrame.ConnID, func(conn *ifaces.Connection) {
		if conn.LastKnownAddr.String() != remote.String() {
			h.log.Trace().Msgf("remote id %d changed remote address %s to %s", abstractFrame.ConnID, conn.LastKnownAddr, remote)
		}
		conn.LastKnownAddr = remote
		// this is responsibility of ping: conn.LastSeen = time.Now()
		crypt = conn.RxCrypt
		ph = conn.ConnPhase
	}) {
		// unknown source, drop frame
		return
	}
	if !validateConnPhase(abstractFrame.FrameType, ph) {
		return
	}
	switch abstractFrame.FrameType {
	case frametypes.FrameTyHandshakeClient:
		h.handleHandshakeClient(abstractFrame, remote)
	case frametypes.FrameTyEncryptedHandshakeClient:
		h.handleEncryptedHandshakeClient(abstractFrame, crypt)
	case frametypes.FrameTyPing:
		h.handlePing(abstractFrame, crypt)
	case frametypes.FrameTyData:
		h.handleData(abstractFrame, crypt)
	case frametypes.FrameTyCtrl:
		h.handleCtrl(abstractFrame, crypt)
	default:
		h.log.Debug().Msgf("unknown frame type %v", abstractFrame.FrameType)
	}
}

func (h *handler) handleHandshakeClient(f *frametypes.AbstractFrame, remote *net.UDPAddr) {
	h.log.Trace().Msgf("received handshake")
	inf, err := marshaller.New().UnmarshalHandshakeClient(f.Body)
	if err != nil {
		h.log.Debug().Err(err).Msg("failed to unmarshal handshake client")
		return
	}
	status := frametypes.HandshakeStatusOK
	if inf.ProtoVersion > frametypes.ProtoVersion {
		status = frametypes.HandshakeProtoTooHigh
	} else if inf.ProtoVersion < frametypes.ProtoVersion {
		status = frametypes.HandshakeProtoTooLow
	}
	if status != frametypes.HandshakeStatusOK {
		h.log.Warn().Msgf("client connected with wrong proto version. Want=%d got=%d. Denying connection", frametypes.ProtoVersion, inf.ProtoVersion)
		err := h.transmitFrame(
			marshaller.New().MarshalHandshakeServer(&frametypes.ServerHandshakeFrame{
				ECDH:         [65]byte{},
				Signature:    [64]byte{},
				Status:       status,
				ConnectionID: f.ConnID,
			}),
			/* tbh conn id here could be zero or whatever */ f.ConnID,
			frametypes.FrameTyHandshakeServer,
			remote)
		if err != nil {
			h.log.Error().Err(err).Msg("failed to transmit failure to client")
		}
		return
	}

	remoteKey, err := ecdh.P256().NewPublicKey(inf.ECDH[:])
	if err != nil {
		h.log.Error().Err(err).Msg("failed to parse ecdh public key")
		return
	}
	srvKey, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		h.log.Error().Err(err).Msg("failed to generate ecdh key")
		return
	}
	sharedSecret, err := srvKey.ECDH(remoteKey)
	if err != nil {
		h.log.Error().Err(err).Msg("failed to perform ecdh exchange")
		return
	}

	connId := util.CSPRNGInt[uint64]()
	sharedSecret = binary.LittleEndian.AppendUint64(sharedSecret, connId)
	sharedSecret = binary.LittleEndian.AppendUint64(sharedSecret, f.ConnID)

	conn := new(ifaces.Connection)
	hashed := sha512.Sum512(sharedSecret)
	keyTx := hashed[:32]
	keyRx := hashed[32:]

	conn.ConnPhase = ifaces.ConnPhaseHandshake
	conn.LastSeen = time.Now()
	conn.LastKnownAddr = remote

	aesTx, err := aes.NewCipher(keyTx)
	if err != nil {
		h.log.Error().Err(err).Msg("failed to create TX AES cipher")
		return
	}
	aesRx, err := aes.NewCipher(keyRx)
	if err != nil {
		h.log.Error().Err(err).Msg("failed to create RX AES cipher")
		return
	}

	aeadTx, err := cipher.NewGCM(aesTx)
	if err != nil {
		h.log.Error().Err(err).Msg("failed to create TX GCM cipher")
		return
	}
	aeadRx, err := cipher.NewGCM(aesRx)
	if err != nil {
		h.log.Error().Err(err).Msg("failed to create RX GCM cipher")
		return
	}

	conn.ConnectionID = connId
	conn.TxCrypt = aeadTx
	conn.RxCrypt = aeadRx
	conn.TxNonce = new(uint64)

	state.ConnMap.Set(connId, conn)

	h.log.Info().Msg("Hello. Connection initialized from remote client")

	err = h.transmitFrame(
		marshaller.New().MarshalHandshakeServer(&frametypes.ServerHandshakeFrame{
			ECDH:         [65]byte(srvKey.PublicKey().Bytes()),
			Signature:    h.id.ProtoSignature(remoteKey, srvKey.PublicKey(), f.ConnID, connId),
			Status:       status,
			ConnectionID: connId,
		}),
		connId,
		frametypes.FrameTyHandshakeServer,
		remote,
	)
	if err != nil {
		h.log.Error().Err(err).Msg("failed to transmit handshake response to client")
		return
	}
}

func (h *handler) handleEncryptedHandshakeClient(f *frametypes.AbstractFrame, crypt cipher.AEAD) {
	h.log.Trace().Msgf("received encrypted handshake")
	inf, err := marshaller.New().UnmarshalEncryptedHandshakeClient(f.Body, crypt)
	if err != nil {
		h.log.Debug().Err(err).Msg("failed to unmarshal handshake client")
		return
	}
	ff := util.NewBitmask[uint32](uint32(inf.FeatFlag))
	if inf.FeatFlag != 1 {
		h.log.Warn().Msg("handshake failure: client didnt declare correct features flags")
		return
	}
	pubaddr := ""
	if !state.ConnMap.Update(f.ConnID, func(conn *ifaces.Connection) {
		h.log.Info().Msg("Valid client connection established")
		conn.Features = ff
		conn.ConnPhase = ifaces.ConnPhaseConnected
		conn.PublicAddr = util.CSPRNGString(32)
		pubaddr = conn.PublicAddr
		conn.LastSeen = time.Now()
		err = h.transmitFrame(
			marshaller.New().MarshalEncryptedHandshakeServer(&frametypes.EncryptedServerHandshakeFrame{
				FeatFlag:    1, // works for now
				PublicAlias: [32]byte([]byte(conn.PublicAddr)),
			}, conn.TxNonce, conn.TxCrypt), conn.ConnectionID, frametypes.FrameTyEncryptedHandshakeServer, conn.LastKnownAddr)
		if err != nil {
			h.log.Error().Err(err).Msg("failed to transmit handshake response to client")
			conn.ConnPhase = ifaces.ConnPhaseRequireCleanup
			return
		}
		err = h.pub.Create(f.ConnID, pubaddr)
		if err != nil {
			h.log.Error().Err(err).Msg("failed to create public key on publisher side! Connection is useless, transmitting bye")
			_ = h.transmitFrame(marshaller.New().MarshalCtrl(&frametypes.CtrlFrame{
				CtrlType:  frametypes.CtrlTypeFatalMsgError,
				CtrlNonce: util.CSPRNGInt[uint16](), // NACKing frame, insufficient fucks given,
				Extra:     []byte("failed to create your endpoint"),
			}, conn.TxNonce, conn.TxCrypt), f.ConnID, frametypes.FrameTyCtrl, conn.LastKnownAddr)
			conn.ConnPhase = ifaces.ConnPhaseRequireCleanup
			go state.ConnMap.Delete(f.ConnID) // go prevents cross-lock.
		}
	}) {
		h.log.Error().Err(err).Msg("handshake failure: client doesnt exist")
		return
	}
}

func (h *handler) handlePing(f *frametypes.AbstractFrame, crypt cipher.AEAD) {
	h.log.Trace().Msgf("received ping")
	ping, err := marshaller.New().UnmarshalPing(f.Body, crypt)
	if err != nil {
		h.log.Warn().Err(err).Msg("failed to unmarshal ping")
		return
	}
	state.ConnMap.Update(f.ConnID, func(conn *ifaces.Connection) {
		conn.LastSeen = time.Now()
		err = h.transmitFrame(marshaller.New().MarshalPing(&frametypes.PingFrame{
			Nonce: ping.Nonce,
		}, conn.TxNonce, conn.TxCrypt), conn.ConnectionID, frametypes.FrameTyPing, conn.LastKnownAddr)
		h.log.Trace().Err(err).Msgf("tx pong")
	}) // dont care if not exist
	if err != nil {
		h.log.Error().Err(err).Msg("failed to transmit ping response to client")
	}
}

func (h *handler) handleData(f *frametypes.AbstractFrame, crypt cipher.AEAD) {
	h.log.Trace().Msgf("received data packet")
	dta, err := marshaller.New().UnmarshalData(f.Body, crypt)
	if err != nil {
		h.log.Warn().Err(err).Msg("failed to unmarshal data")
		return
	}
	err = h.pub.Push(f.ConnID, dta.Data)
	if err != nil {
		h.log.Error().Err(err).Msg("failed to push data")
		return
	}
}

func (h *handler) handleCtrl(f *frametypes.AbstractFrame, crypt cipher.AEAD) {
	ctr, err := marshaller.New().UnmarshalCtrl(f.Body, crypt)
	if err != nil {
		h.log.Error().Err(err).Msg("failed to unmarshal ctrl. Connection may be dead soon.")
		return
	}

	if ctr.CtrlType == frametypes.CtrlTypeACK {
		h.registerAck(f.ConnID, ctr.CtrlNonce)
		return
	}

	if isAckingFrame(ctr.CtrlType) {
		h.log.Trace().Msgf("sending ACK")
		// each ctrl frame requires ack, even if its bursted duplicate received frame. ACK may be lost too, after all.
		state.ConnMap.Update(f.ConnID, func(conn *ifaces.Connection) {
			go h.transmitFrame(marshaller.New().MarshalCtrl(&frametypes.CtrlFrame{
				CtrlType:  frametypes.CtrlTypeACK,
				CtrlNonce: ctr.CtrlNonce,
				Extra:     make([]byte, 0, 0),
			}, conn.TxNonce, conn.TxCrypt), conn.ConnectionID, frametypes.FrameTyCtrl, conn.LastKnownAddr)
			// dont care for err, best we can do log and callee logs anyway.
		})
	}

	if h.remoteCtrlIsDupe(f.ConnID, ctr.CtrlNonce) {
		return
	}

	switch ctr.CtrlType {
	case frametypes.CtrlTypeGetSubCount:
		go h.handleCtrlTypeGetSubCount(ctr, f)
	case frametypes.CtrlTypeFatalMsgError:
		go h.handleCtrlTypeFatalMsgError(ctr, f)
	case frametypes.CtrlTypeFatalError:
		go h.handleCtrlTypeFatalError(ctr, f)
	case frametypes.CtrlTypeBye:
		go h.handleCtrlTypeBye(ctr, f)
	}
}

func isAckingFrame(t frametypes.CtrlType) bool {
	if t > 200 { // works? works.
		return false
	}
	return true
}

func validateConnPhase(ft frametypes.FrameType, ph ifaces.ConnPhase) bool {
	switch true {
	case ph == ifaces.ConnPhaseUndefined && ft == frametypes.FrameTyHandshakeClient:
		fallthrough
	case ph == ifaces.ConnPhaseHandshake && ft == frametypes.FrameTyEncryptedHandshakeClient:
		return true
	}
	return ph == ifaces.ConnPhaseConnected && (ft != frametypes.FrameTyHandshakeClient && ft != frametypes.FrameTyEncryptedHandshakeClient)
}

func (h *handler) Close() error {
	h.stop.Send(struct{}{})
	return nil
}
