package util

import (
	"sync"

	"github.com/rs/zerolog"
)

type ChanDemux[T any] struct {
	mu    *sync.RWMutex
	in    chan T
	out   []chan T
	close chan struct{}
	l     zerolog.Logger
}

func NewChanDemux[T any](l zerolog.Logger) *ChanDemux[T] {
	c := &ChanDemux[T]{
		mu:    &sync.RWMutex{},
		in:    make(chan T),
		out:   make([]chan T, 0),
		close: make(chan struct{}),
		l:     l,
	}
	go c.run()
	return c
}

func (c *ChanDemux[T]) run() {
	for {
		select {
		case val := <-c.in:
			c.mu.RLock()
			// todo: enhance with go and wg if this will do any real heavy lifting
			for _, ch := range c.out {
				select {
				case ch <- val:
				default:
					c.l.Warn().Msg("channel full! Data is lost")
				}
			}
			c.mu.RUnlock()

		case <-c.close:
			c.mu.Lock()
			defer c.mu.Unlock()
			for i := range c.out {
				close(c.out[i])
			}
			c.out = c.out[:0]
			close(c.in)
			close(c.close)
			return
		}
	}
}

func (c *ChanDemux[T]) Send(v T) {
	defer func() { recover() /* don't panic after Close() */ }()
	c.in <- v
}

func (c *ChanDemux[T]) Consumer(buff *int) <-chan T {
	c.mu.Lock()
	defer c.mu.Unlock()
	var ch chan T
	if buff != nil {
		ch = make(chan T, *buff)
	} else {
		ch = make(chan T)
	}
	c.out = append(c.out, ch)
	return ch
}

func (c *ChanDemux[T]) Close() {
	defer func() { recover() /* don't panic after Close() */ }()
	c.close <- struct{}{}
}

func (c *ChanDemux[T]) Consumers() uint64 {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return uint64(len(c.out))
}

func (c *ChanDemux[T]) CloseChan(ch <-chan T) {
	c.mu.Lock()
	defer c.mu.Unlock()
	idx := -1
	for i := range c.out {
		if c.out[i] == ch {
			idx = i
			break
		}
	}
	if idx == -1 {
		return
	}
	close(c.out[idx])
	c.out = append(c.out[:idx], c.out[idx+1:]...)
}
