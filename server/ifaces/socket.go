package ifaces

import (
	"io"
	"net"
)

type UDPSock interface {
	UDPReader
	UDPWriter
	io.Closer
}

type UDPReader interface {
	ReadFromUDP(b []byte) (n int, addr *net.UDPAddr, err error)
}

type UDPWriter interface {
	WriteToUDP(b []byte, addr *net.UDPAddr) (int, error)
}
