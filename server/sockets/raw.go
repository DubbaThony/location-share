package sockets

import (
	"net"

	"github.com/DubbaThony/share-server/ifaces"
	"github.com/rs/zerolog"
)

func OpenUdp(l zerolog.Logger, cfg ifaces.Config) (ifaces.UDPSock, error) {
	rsock, err := net.ListenUDP("udp", &net.UDPAddr{
		IP:   net.ParseIP("0.0.0.0"),
		Port: int(cfg.UDPPort()),
	})
	if err != nil {
		return nil, err
	}
	sock := ifaces.UDPSock(rsock)
	if cfg.DebugFaultySocket() {
		sock = NewFaultySocket(sock, l, cfg.DebugFaultySocketRXPLPerc(), cfg.DebugFaultySocketTXPLPerc())
	}
	if cfg.MeteredSocket() {
		sock = NewMeteredSocket(sock, l)
	}
	return sock, nil
}
