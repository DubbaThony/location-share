package ifaces

type BasicResponse[T any] struct {
	Status bool    `json:"status"`
	Error  *string `json:"error,omitempty"`
	Result T       `json:"result"`
}

type MsgType string

const (
	WsMsgTypePublisherGone = "publisher_gone"
)

type WsMsg[T any] struct {
	MsgType MsgType `json:"msg_type"`
	Pld     *T      `json:"pld,omitempty"`
}

type AppConfig struct {
	Identity              string  `json:"identity"`
	PrefferedAppBuildHash *string `json:"preffered_app_build_hash"`
	LocalSigner           *string `json:"local_signer"`
}
