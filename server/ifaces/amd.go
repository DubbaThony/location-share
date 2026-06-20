package ifaces

type AppMeta interface {
	Signer() *string
	Hash() *string
}
