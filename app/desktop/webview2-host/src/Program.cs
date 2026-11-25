using System;
using System.IO;
using System.Windows.Forms;

namespace Orderly.Desktop
{
    internal static class Program
    {
        [STAThread]
        private static int Main(string[] args)
        {
            if (args.Length == 2 && String.Equals(args[0], "--self-test", StringComparison.Ordinal))
            {
                return SelfTest.Run(args[1]);
            }

            LaunchOptions options = null;
            try
            {
                options = LaunchOptions.Parse(args);
                Directory.CreateDirectory(options.DataRoot);
                DiagnosticLog.Write(options.DataRoot, "WebView2 host starting.");

                if (options.ActivateOnly)
                {
                    return HostActivation.ActivateRunningInstance(options.DataRoot) ? 0 : 2;
                }

                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);

                HostActivation activation;
                if (!HostActivation.TryAcquire(options.DataRoot, out activation))
                {
                    HostActivation.ActivateRunningInstance(options.DataRoot);
                    return 0;
                }

                using (activation)
                {
                    activation.Start();
                    using (MainForm form = new MainForm(options, activation))
                    {
                        Application.Run(form);
                    }
                }
                DiagnosticLog.Write(options.DataRoot, "WebView2 host stopped.");
                return 0;
            }
            catch (Exception exception)
            {
                string dataRoot = options == null ? null : options.DataRoot;
                DiagnosticLog.Write(dataRoot, "Fatal WebView2 host error: " + exception);
                MessageBox.Show(
                    "Orderly could not start its WebView2 window.\r\n\r\n"
                    + exception.Message,
                    "Orderly",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error
                );
                return 1;
            }
        }
    }
}
