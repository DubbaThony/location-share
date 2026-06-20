package app_metadata

import (
	"archive/zip"
	"crypto/sha512"
	"embed"
	"encoding/hex"
	"io"
	"strings"

	"github.com/DubbaThony/share-server/ifaces"
	"github.com/DubbaThony/share-server/util"
	"github.com/rs/zerolog"
)

type appMetadata struct {
	ah   *string
	sgnr *string
}

func New(l zerolog.Logger, embedFs embed.FS) ifaces.AppMeta {
	ah, sgn := appHash(l, embedFs)
	return &appMetadata{
		ah:   ah,
		sgnr: sgn,
	}
}

func (a *appMetadata) Signer() *string {
	return a.sgnr
}
func (a *appMetadata) Hash() *string {
	return a.ah
}

func appHash(l zerolog.Logger, staticFiles embed.FS) (appHash *string, signer *string) {
	f, err := staticFiles.Open("static/pl.dubba.share.apk")
	if err != nil {
		l.Warn().Err(err).Msg("Failed to open local embeded apk file. Was backend built correctly?")
		return nil, nil
	}
	h := sha512.New()
	_, err = io.Copy(h, f)
	if err != nil {
		l.Error().Err(err).Msg("WTF: cannot read embeded files?")
		return nil, nil
	}
	stat, err := f.Stat()
	if err != nil {
		l.Error().Err(err).Msg("WTF: cannot stat embeded files?")
		return nil, nil
	}
	ra, ok := f.(io.ReaderAt)
	if !ok {
		l.Error().Msg("WTF: embeded file isnt seekable")
	}

	r, err := zip.NewReader(ra, stat.Size())
	if err != nil {
		l.Error().Err(err).Msg("failed to open apk as zip")
		return nil, nil
	}
	certName := "META-INF/SHARE.RSA"
	for _, f := range r.File {
		if !strings.HasPrefix(f.Name, "META-INF/") {
			continue
		}
		upper := strings.ToUpper(f.Name)
		if strings.HasSuffix(upper, ".RSA") || strings.HasSuffix(upper, ".EC") || strings.HasSuffix(upper, ".DSA") {
			certName = f.Name
		}
	}
	fh, err := r.Open(certName)
	if err != nil {
		l.Error().Err(err).Msgf("failed to open %s cert", certName)
		return nil, nil
	}
	cert, err := io.ReadAll(fh)
	if err != nil {
		l.Error().Err(err).Msgf("failed to read %s cert", certName)
		return nil, nil
	}

	l.Info().Msg("embeded APK file exists")
	return util.P(hex.EncodeToString(h.Sum(nil))), util.P(hex.EncodeToString(cert))
}
