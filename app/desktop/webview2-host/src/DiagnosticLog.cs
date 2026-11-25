using System;
using System.Globalization;
using System.IO;
using System.Text;

namespace Orderly.Desktop
{
    internal static class DiagnosticLog
    {
        private static readonly object Sync = new object();

        public static string PathFor(string dataRoot)
        {
            return Path.Combine(dataRoot, "logs", "webview2-host.log");
        }

        public static void Write(string dataRoot, string message)
        {
            if (String.IsNullOrWhiteSpace(dataRoot))
            {
                return;
            }
            try
            {
                lock (Sync)
                {
                    string path = PathFor(dataRoot);
                    Directory.CreateDirectory(Path.GetDirectoryName(path));
                    string line = DateTimeOffset.Now.ToString(
                        "yyyy-MM-dd HH:mm:ss.fff zzz",
                        CultureInfo.InvariantCulture
                    ) + " " + (message ?? String.Empty) + Environment.NewLine;
                    File.AppendAllText(path, line, new UTF8Encoding(false));
                }
            }
            catch
            {
                // Diagnostics must never change the application outcome.
            }
        }
    }
}
