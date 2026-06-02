package protocol

import (
	"fmt"
	"sync"
	"time"

	"net"

	"github.com/DubbaThony/share-server/ifaces"
	"github.com/rs/zerolog"
)

const (
	meterPeriod    = 10 * time.Second
	meterChanDepth = 1024
)

// MeteredSocket counts rx/tx bytes on the socket and periodically logs the values
type MeteredSocket struct {
	inner ifaces.UDPSock
	l     zerolog.Logger

	rxCh chan int
	txCh chan int
	done chan struct{}
	once sync.Once
	wg   sync.WaitGroup
}

func NewMeteredSocket(sock ifaces.UDPSock, l zerolog.Logger) ifaces.UDPSock {
	m := &MeteredSocket{
		inner: sock,
		l:     l,
		rxCh:  make(chan int, meterChanDepth),
		txCh:  make(chan int, meterChanDepth),
		done:  make(chan struct{}),
	}
	m.wg.Add(1)
	go m.meterLoop()
	return m
}

func (m *MeteredSocket) ReadFromUDP(b []byte) (int, *net.UDPAddr, error) {
	n, addr, err := m.inner.ReadFromUDP(b)
	if n > 0 {
		select {
		case m.rxCh <- n:
		default: // chan full, drop the sample
		}
	}
	return n, addr, err
}

func (m *MeteredSocket) WriteToUDP(b []byte, addr *net.UDPAddr) (int, error) {
	n, err := m.inner.WriteToUDP(b, addr)
	if n > 0 {
		select {
		case m.txCh <- n:
		default:
		}
	}
	return n, err
}

func (m *MeteredSocket) Close() error {
	var err error
	m.once.Do(func() {
		close(m.done)
		m.wg.Wait()
		err = m.inner.Close()
	})
	return err
}

func (m *MeteredSocket) meterLoop() {
	defer m.wg.Done()
	ticker := time.NewTicker(meterPeriod)
	defer ticker.Stop()

	var rxBytes, txBytes int64
	seconds := meterPeriod.Seconds()

	for {
		select {
		case n := <-m.rxCh:
			rxBytes += int64(n)
		case n := <-m.txCh:
			txBytes += int64(n)
		case <-ticker.C:
			rxRate := float64(rxBytes) / seconds
			txRate := float64(txBytes) / seconds
			evt := m.l.Info()
			if rxBytes == 0 && txBytes == 0 {
				evt = m.l.Debug()
			}
			evt.Str("rx", humanBytes(rxRate)+"/s").
				Str("tx", humanBytes(txRate)+"/s").
				Msg("udp traffic counters")
			rxBytes = 0
			txBytes = 0
		case <-m.done:
			return
		}
	}
}

func humanBytes(b float64) string {
	const (
		kib = 1 << 10
		mib = 1 << 20
		gib = 1 << 30
	)
	switch {
	case b >= gib:
		return fmt.Sprintf("%.2f GiB", b/gib)
	case b >= mib:
		return fmt.Sprintf("%.2f MiB", b/mib)
	case b >= kib:
		return fmt.Sprintf("%.2f KiB", b/kib)
	default:
		return fmt.Sprintf("%.0f B", b)
	}
}
