package sockets

import (
	"math/rand"
	"net"

	"github.com/DubbaThony/share-server/ifaces"
	"github.com/rs/zerolog"
)

// FaultySocket is a socket that depending on configuration, drops certain percentage of packets unpredictably.
// Useful for testing things such as reaibility of acking frames.

type flts struct {
	sock                ifaces.UDPSock
	failChancePercentRX uint8
	failChancePercentTX uint8
}

func NewFaultySocket(sock ifaces.UDPSock, l zerolog.Logger, failChancePercentRX, failChancePercentTX uint8) ifaces.UDPSock {
	l.Warn().Msg("Initializing faulty socket! Do not run in real world!")
	return &flts{
		sock:                sock,
		failChancePercentRX: failChancePercentRX,
		failChancePercentTX: failChancePercentTX,
	}
}

func (f flts) ReadFromUDP(b []byte) (int, *net.UDPAddr, error) {
	for {
		n, a, e := f.sock.ReadFromUDP(b)
		if rng(f.failChancePercentRX) {
			return n, a, e
		}
	}
}

func (f flts) WriteToUDP(b []byte, addr *net.UDPAddr) (int, error) {
	if rng(f.failChancePercentTX) {
		return f.sock.WriteToUDP(b, addr)
	}
	return len(b), nil
}

func (f flts) Close() error {
	return f.sock.Close()
}

func rng(chance uint8) bool {
	return uint8(rand.Intn(100)) >= chance
}
