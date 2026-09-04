package socket

import (
	"os"
	"runtime"
	"strings"
	"testing"
)

func TestParseSingboxVersion(t *testing.T) {
	tests := []struct {
		name   string
		output string
		want   string
	}{
		{name: "stable", output: "sing-box version 1.13.12\n", want: "1.13.12"},
		{name: "v prefix", output: "sing-box version v1.14.0\n", want: "1.14.0"},
		{name: "details", output: "sing-box version 1.13.12\n\nEnvironment: go1.25 linux/amd64\n", want: "1.13.12"},
		{name: "invalid", output: "unknown", want: ""},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := parseSingboxVersion(tt.output); got != tt.want {
				t.Fatalf("parseSingboxVersion() = %q, want %q", got, tt.want)
			}
		})
	}
}

func TestSingboxDownloadURLsForVersion(t *testing.T) {
	urls := singboxDownloadURLsForVersion("", "1.13.12")
	if len(urls) == 0 {
		t.Fatal("expected at least one download URL")
	}
	wantAsset := "sing-box-1.13.12-linux-" + runtime.GOARCH + ".tar.gz"
	if !strings.Contains(urls[0], "/v1.13.12/") || !strings.HasSuffix(urls[0], wantAsset) {
		t.Fatalf("unexpected primary download URL: %s", urls[0])
	}
}

func TestFileSHA256(t *testing.T) {
	path := t.TempDir() + "/payload"
	if err := os.WriteFile(path, []byte("abc"), 0o600); err != nil {
		t.Fatal(err)
	}
	got, err := fileSHA256(path)
	if err != nil {
		t.Fatal(err)
	}
	const want = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
	if got != want {
		t.Fatalf("fileSHA256() = %s, want %s", got, want)
	}
}
