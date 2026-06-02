package identity

import (
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/binary"
	"encoding/hex"
	"os"

	"github.com/DubbaThony/share-server/ifaces"
	"github.com/rs/zerolog"
)

const identFile = "server_identity.bin"

type id struct {
	l   zerolog.Logger
	pk  ed25519.PrivateKey
	pub ed25519.PublicKey
}

func New(l zerolog.Logger) ifaces.Identity {
	exists := true
	l.Trace().Msg("os.Stat(identFile)")
	fi, err := os.Stat(identFile)
	if err != nil {
		if os.IsNotExist(err) {
			l.Warn().Msgf("Identity of server is undefiend (`%s` file doesnt exist)", identFile)
			exists = false
		} else {
			l.Panic().Err(err).Msgf("failed to stat identity file")
		}
	}
	if exists && fi.Size() != 64 {
		l.Error().Msgf("identity file is invalid size, deleting it!")
		err = os.Remove(identFile)
		if err != nil {
			l.Panic().Err(err).Msgf("Failed to delete invalid identity file")
		}
		exists = false
	}
	var idBytes []byte
	if exists {
		l.Trace().Msgf("identity file found, reading")
		idBytes, err = os.ReadFile(identFile)
		if err != nil {
			l.Panic().Err(err).Msg("failed to read identity file")
		}
	} else {
		l.Info().Msgf("Generating identity...")
		_, idBytes, err = ed25519.GenerateKey(rand.Reader)
		if err != nil {
			l.Panic().Err(err).Msg("Failed to generate identity")
		}
		l.Trace().Msg("identity generated")
		l.Info().Msgf("persisting identity to %s", identFile)
		err = os.WriteFile(identFile, idBytes, 0600)
		if err != nil {
			l.Panic().Err(err).Msg("failed to write identity file!")
		}
	}
	pub := ed25519.PrivateKey(idBytes).Public()
	pubT, ok := pub.(ed25519.PublicKey)
	if !ok {
		panic("WTF: ed25519 privk.Public() returned invalid type")
	}

	l.Info().Msgf("Initializing with identity: %s", hex.EncodeToString(pubT))
	return &id{l: l, pk: idBytes, pub: pubT}
}

func (i *id) ProtoSignature(ecdhClient, ecdhServer *ecdh.PublicKey, remoteConnId, serverConnId uint64) (out [64]byte) {
	// stack alloc for perf
	buff := [65 + 65 + 8 + 8]byte{}

	copy(buff[:], ecdhClient.Bytes())
	copy(buff[65:], ecdhServer.Bytes())
	binary.LittleEndian.PutUint64(buff[65+65:65+65+8], remoteConnId)
	binary.LittleEndian.PutUint64(buff[65+65+8:65+65+16], serverConnId)
	out = [64]byte(ed25519.Sign(i.pk, buff[:]))
	return out
}

func (i *id) Signature(pld []byte) []byte {
	return ed25519.Sign(i.pk, pld)
}

func (i *id) Identity() []byte {
	return i.pub
}
