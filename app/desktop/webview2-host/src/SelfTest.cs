using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace Orderly.Desktop
{
    internal static class SelfTest
    {
        public static int Run(string resultPath)
        {
            List<string> failures = new List<string>();
            string root = Path.Combine(
                Path.GetTempPath(),
                "orderly-webview2-self-test-" + Guid.NewGuid().ToString("N")
            );
            try
            {
                LaunchOptions options = LaunchOptions.Parse(
                    new string[]
                    {
                        "--url",
                        "http://127.0.0.1:18080/",
                        "--data-root",
                        root,
                        "--mode",
                        "test"
                    }
                );
                Check(failures, options.Url.Port == 18080, "valid local URL port");
                Check(
                    failures,
                    NavigationPolicy.Allows(
                        new Uri("http://127.0.0.1:18080/assets/app.js"),
                        options.Url
                    ),
                    "same origin navigation"
                );
                Check(
                    failures,
                    !NavigationPolicy.Allows(
                        new Uri("http://127.0.0.1:18081/"),
                        options.Url
                    ),
                    "different port rejected"
                );
                Check(
                    failures,
                    !NavigationPolicy.Allows(
                        new Uri("https://example.com/"),
                        options.Url
                    ),
                    "external origin rejected"
                );
                Check(
                    failures,
                    NavigationPolicy.AllowsPreview(
                        new Uri("http://127.0.0.1:18081/"),
                        options.Url
                    ),
                    "loopback preview origin accepted in a separate window"
                );
                Check(
                    failures,
                    !NavigationPolicy.AllowsPreview(
                        new Uri("http://localhost:18081/"),
                        options.Url
                    ),
                    "preview host must be literal loopback"
                );
                Check(
                    failures,
                    !NavigationPolicy.AllowsPreview(
                        new Uri("http://127.0.0.1:18081/path"),
                        options.Url
                    ),
                    "preview path restricted to application root"
                );
                Reject(failures, "https://127.0.0.1:18080/");
                Reject(failures, "http://localhost:18080/");
                Reject(failures, "file:///C:/Windows/win.ini");
                Reject(failures, "http://127.0.0.1:18080/?path=C:/Windows");
                Check(
                    failures,
                    HostActivation.BuildSuffix(root)
                        == HostActivation.BuildSuffix(root.ToUpperInvariant()),
                    "activation identity is path-case stable"
                );
            }
            catch (Exception exception)
            {
                failures.Add("unexpected exception: " + exception);
            }
            finally
            {
                try
                {
                    if (Directory.Exists(root))
                    {
                        Directory.Delete(root, true);
                    }
                }
                catch
                {
                }
            }

            StringBuilder result = new StringBuilder();
            if (failures.Count == 0)
            {
                result.AppendLine("PASS");
                result.AppendLine("WebView2 host option and navigation checks passed.");
            }
            else
            {
                result.AppendLine("FAIL");
                foreach (string failure in failures)
                {
                    result.AppendLine(failure);
                }
            }

            string fullResultPath = Path.GetFullPath(resultPath);
            Directory.CreateDirectory(Path.GetDirectoryName(fullResultPath));
            File.WriteAllText(fullResultPath, result.ToString(), new UTF8Encoding(false));
            return failures.Count == 0 ? 0 : 1;
        }

        private static void Reject(List<string> failures, string url)
        {
            try
            {
                LaunchOptions.ParseLocalUrl(url);
                failures.Add("URL should be rejected: " + url);
            }
            catch (ArgumentException)
            {
            }
        }

        private static void Check(List<string> failures, bool condition, string description)
        {
            if (!condition)
            {
                failures.Add("failed: " + description);
            }
        }
    }
}
