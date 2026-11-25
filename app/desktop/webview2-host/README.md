# Orderly WebView2 Host

This project is the Windows display shell for Orderly. It loads the local
front-end from `http://127.0.0.1:<port>/` and never owns business state or file
permissions.

The build uses the Windows .NET Framework 4.8 C# compiler and the pinned
Microsoft WebView2 SDK files in `../webview2-sdk/`.

Run `scripts/build-webview2-host.ps1` from the repository root. The host
supports a non-GUI self-test through
`OrderlyWebView2Host.exe --self-test <result-file>`.
