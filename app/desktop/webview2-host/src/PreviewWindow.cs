using System;
using System.Drawing;
using System.IO;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace Orderly.Desktop
{
    internal sealed class PreviewWindow : Form
    {
        private readonly LaunchOptions options;
        private readonly Uri origin;
        private readonly WebView2 webView;

        public PreviewWindow(LaunchOptions options, Uri origin)
        {
            this.options = options;
            this.origin = origin;
            Text = "Orderly Preview";
            MinimumSize = new Size(820, 560);
            Size = new Size(1280, 820);
            StartPosition = FormStartPosition.CenterParent;
            BackColor = Color.White;

            webView = new WebView2();
            webView.Dock = DockStyle.Fill;
            webView.AllowExternalDrop = false;
            webView.DefaultBackgroundColor = Color.White;
            Controls.Add(webView);
            Shown += PreviewWindowShown;
        }

        private async void PreviewWindowShown(object sender, EventArgs e)
        {
            try
            {
                CoreWebView2CreationProperties creation = new CoreWebView2CreationProperties();
                creation.UserDataFolder = Path.Combine(
                    options.DataRoot,
                    "webview2-preview"
                );
                if (!String.IsNullOrWhiteSpace(options.RuntimeFolder))
                {
                    creation.BrowserExecutableFolder = options.RuntimeFolder;
                }
                webView.CreationProperties = creation;
                await webView.EnsureCoreWebView2Async();

                CoreWebView2 core = webView.CoreWebView2;
                core.Settings.AreDevToolsEnabled = false;
                core.Settings.AreDefaultContextMenusEnabled = false;
                core.Settings.IsStatusBarEnabled = false;
                core.Settings.IsGeneralAutofillEnabled = false;
                core.Settings.IsPasswordAutosaveEnabled = false;
                core.NavigationStarting += NavigationStarting;
                core.NewWindowRequested += NewWindowRequested;
                core.PermissionRequested += PermissionRequested;
                core.DownloadStarting += DownloadStarting;
                core.Navigate(origin.AbsoluteUri);
            }
            catch (Exception exception)
            {
                DiagnosticLog.Write(
                    options.DataRoot,
                    "Preview WebView2 initialization failed: " + exception
                );
                MessageBox.Show(
                    this,
                    "Preview window could not start.\r\n\r\n" + exception.Message,
                    "Orderly Preview",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error
                );
                Close();
            }
        }

        private void NavigationStarting(
            object sender,
            CoreWebView2NavigationStartingEventArgs e
        )
        {
            Uri target;
            if (!Uri.TryCreate(e.Uri, UriKind.Absolute, out target)
                || !NavigationPolicy.Allows(target, origin))
            {
                e.Cancel = true;
            }
        }

        private void NewWindowRequested(
            object sender,
            CoreWebView2NewWindowRequestedEventArgs e
        )
        {
            e.Handled = true;
            Uri target;
            if (Uri.TryCreate(e.Uri, UriKind.Absolute, out target)
                && NavigationPolicy.Allows(target, origin))
            {
                webView.CoreWebView2.Navigate(target.AbsoluteUri);
            }
        }

        private static void PermissionRequested(
            object sender,
            CoreWebView2PermissionRequestedEventArgs e
        )
        {
            e.State = CoreWebView2PermissionState.Deny;
        }

        private static void DownloadStarting(
            object sender,
            CoreWebView2DownloadStartingEventArgs e
        )
        {
            e.Cancel = true;
            e.Handled = true;
        }
    }
}
