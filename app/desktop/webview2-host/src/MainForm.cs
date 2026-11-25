using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace Orderly.Desktop
{
    internal sealed class MainForm : Form
    {
        private readonly LaunchOptions options;
        private readonly HostActivation activation;
        private readonly WebView2 webView;
        private readonly Timer healthTimer;
        private bool allowClose;
        private bool everHealthy;
        private bool closingRequested;
        private int healthFailures;

        public MainForm(LaunchOptions options, HostActivation activation)
        {
            this.options = options;
            this.activation = activation;
            Text = "\u5F52\u5E8F Orderly";
            MinimumSize = new Size(960, 640);
            BackColor = Color.White;
            WindowStateStore.Apply(this, WindowStateStore.Load(options.DataRoot));

            webView = new WebView2();
            webView.Dock = DockStyle.Fill;
            webView.AllowExternalDrop = false;
            webView.DefaultBackgroundColor = Color.White;
            Controls.Add(webView);

            healthTimer = new Timer();
            healthTimer.Interval = 2000;
            healthTimer.Tick += HealthTimerTick;

            Shown += MainFormShown;
            FormClosing += MainFormClosing;
            FormClosed += MainFormClosed;
            activation.Activated += ActivateFromSecondInstance;
        }

        private async void MainFormShown(object sender, EventArgs e)
        {
            try
            {
                await InitializeWebViewAsync();
                healthTimer.Start();
            }
            catch (Exception exception)
            {
                DiagnosticLog.Write(options.DataRoot, "WebView2 initialization failed: " + exception);
                MessageBox.Show(
                    this,
                    "WebView2 could not start.\r\n\r\n" + exception.Message,
                    "Orderly",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error
                );
                allowClose = true;
                Close();
            }
        }

        private async Task InitializeWebViewAsync()
        {
            CoreWebView2CreationProperties creation = new CoreWebView2CreationProperties();
            creation.UserDataFolder = Path.Combine(options.DataRoot, "webview2");
            if (!String.IsNullOrWhiteSpace(options.RuntimeFolder))
            {
                string executable = Path.Combine(
                    options.RuntimeFolder,
                    "msedgewebview2.exe"
                );
                if (!File.Exists(executable))
                {
                    throw new InvalidOperationException(
                        "The configured WebView2 Runtime folder is invalid: "
                        + options.RuntimeFolder
                    );
                }
                creation.BrowserExecutableFolder = options.RuntimeFolder;
            }
            else
            {
                CoreWebView2Environment.GetAvailableBrowserVersionString();
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
            core.ProcessFailed += ProcessFailed;
            core.Navigate(options.Url.AbsoluteUri);
            everHealthy = true;
        }

        private void NavigationStarting(
            object sender,
            CoreWebView2NavigationStartingEventArgs e
        )
        {
            Uri target;
            if (!Uri.TryCreate(e.Uri, UriKind.Absolute, out target)
                || !NavigationPolicy.Allows(target, options.Url))
            {
                e.Cancel = true;
                if (e.IsUserInitiated)
                {
                    OpenExternal(target);
                }
            }
        }

        private void NewWindowRequested(
            object sender,
            CoreWebView2NewWindowRequestedEventArgs e
        )
        {
            e.Handled = true;
            Uri target;
            if (!Uri.TryCreate(e.Uri, UriKind.Absolute, out target))
            {
                return;
            }
            if (NavigationPolicy.Allows(target, options.Url))
            {
                webView.CoreWebView2.Navigate(target.AbsoluteUri);
            }
            else if (e.IsUserInitiated)
            {
                OpenExternal(target);
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

        private void ProcessFailed(object sender, CoreWebView2ProcessFailedEventArgs e)
        {
            DiagnosticLog.Write(
                options.DataRoot,
                "WebView2 process failed: " + e.ProcessFailedKind
            );
            if (e.ProcessFailedKind == CoreWebView2ProcessFailedKind.BrowserProcessExited)
            {
                allowClose = true;
                BeginInvoke(new Action(Close));
            }
        }

        private async void MainFormClosing(object sender, FormClosingEventArgs e)
        {
            if (allowClose)
            {
                return;
            }
            e.Cancel = true;
            if (closingRequested)
            {
                return;
            }

            closingRequested = true;
            ShutdownRequestResult result = await ShutdownClient.RequestAsync(options.Url);
            if (result == ShutdownRequestResult.Accepted
                || result == ShutdownRequestResult.Unavailable)
            {
                allowClose = true;
                Close();
                return;
            }

            closingRequested = false;
            string message = result == ShutdownRequestResult.ExecutionInProgress
                ? "A task is still running. Wait for it to finish before closing Orderly."
                : "Orderly could not confirm a safe shutdown.";
            MessageBox.Show(
                this,
                message,
                "Orderly",
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning
            );
        }

        private async void HealthTimerTick(object sender, EventArgs e)
        {
            bool alive = await ShutdownClient.IsAliveAsync(options.Url);
            if (alive)
            {
                everHealthy = true;
                healthFailures = 0;
                return;
            }
            if (!everHealthy)
            {
                return;
            }
            healthFailures++;
            if (healthFailures >= 3)
            {
                healthTimer.Stop();
                allowClose = true;
                Close();
            }
        }

        private void MainFormClosed(object sender, FormClosedEventArgs e)
        {
            healthTimer.Stop();
            healthTimer.Dispose();
            WindowStateStore.Save(options.DataRoot, this);
            activation.Activated -= ActivateFromSecondInstance;
        }

        private void ActivateFromSecondInstance()
        {
            if (IsDisposed || !IsHandleCreated)
            {
                return;
            }
            BeginInvoke(new Action(RestoreWindow));
        }

        private void RestoreWindow()
        {
            if (WindowState == FormWindowState.Minimized)
            {
                WindowState = FormWindowState.Normal;
            }
            Show();
            Activate();
            BringToFront();
        }

        private void OpenExternal(Uri target)
        {
            if (!NavigationPolicy.IsHttpExternal(target, options.Url))
            {
                return;
            }
            try
            {
                Process.Start(new ProcessStartInfo(target.AbsoluteUri)
                {
                    UseShellExecute = true
                });
            }
            catch (Win32Exception exception)
            {
                DiagnosticLog.Write(
                    options.DataRoot,
                    "Unable to open external link: " + exception.Message
                );
            }
        }
    }
}
