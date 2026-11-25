using System;
using System.Collections.Generic;
using System.IO;

namespace Orderly.Desktop
{
    internal sealed class LaunchOptions
    {
        private LaunchOptions()
        {
        }

        public Uri Url { get; private set; }

        public string DataRoot { get; private set; }

        public string RuntimeFolder { get; private set; }

        public string Mode { get; private set; }

        public bool ActivateOnly { get; private set; }

        public static LaunchOptions Parse(string[] args)
        {
            Dictionary<string, string> values = ReadArguments(args);
            LaunchOptions options = new LaunchOptions();
            options.ActivateOnly = values.ContainsKey("activate");
            options.Mode = GetValue(values, "mode", "packaged");
            options.DataRoot = RequireAbsolutePath(
                GetRequiredValue(values, "data-root"),
                "data-root"
            );

            string runtimeFolder = GetValue(values, "runtime-folder", null);
            options.RuntimeFolder = String.IsNullOrWhiteSpace(runtimeFolder)
                ? null
                : RequireAbsolutePath(runtimeFolder, "runtime-folder");

            if (!options.ActivateOnly)
            {
                options.Url = ParseLocalUrl(GetRequiredValue(values, "url"));
            }

            return options;
        }

        public static Uri ParseLocalUrl(string value)
        {
            Uri uri;
            if (!Uri.TryCreate(value, UriKind.Absolute, out uri)
                || !String.Equals(uri.Scheme, Uri.UriSchemeHttp, StringComparison.OrdinalIgnoreCase)
                || !String.Equals(uri.Host, "127.0.0.1", StringComparison.Ordinal)
                || uri.Port < 1
                || uri.Port > 65535
                || !String.IsNullOrEmpty(uri.UserInfo)
                || !String.Equals(uri.AbsolutePath, "/", StringComparison.Ordinal)
                || !String.IsNullOrEmpty(uri.Query)
                || !String.IsNullOrEmpty(uri.Fragment))
            {
                throw new ArgumentException(
                    "url must be an http://127.0.0.1:<port>/ address without query or fragment"
                );
            }
            return uri;
        }

        private static Dictionary<string, string> ReadArguments(string[] args)
        {
            Dictionary<string, string> values = new Dictionary<string, string>(
                StringComparer.OrdinalIgnoreCase
            );
            for (int index = 0; index < args.Length; index++)
            {
                string argument = args[index] ?? String.Empty;
                if (!argument.StartsWith("--", StringComparison.Ordinal))
                {
                    throw new ArgumentException("unexpected argument: " + argument);
                }

                string name = argument.Substring(2);
                string value = null;
                int separator = name.IndexOf('=');
                if (separator >= 0)
                {
                    value = name.Substring(separator + 1);
                    name = name.Substring(0, separator);
                }

                if (String.Equals(name, "activate", StringComparison.OrdinalIgnoreCase))
                {
                    if (value != null)
                    {
                        throw new ArgumentException("--activate does not accept a value");
                    }
                    values[name] = String.Empty;
                    continue;
                }

                if (value == null)
                {
                    if (index + 1 >= args.Length
                        || (args[index + 1] ?? String.Empty).StartsWith("--", StringComparison.Ordinal))
                    {
                        throw new ArgumentException("missing value for --" + name);
                    }
                    value = args[++index];
                }
                if (String.IsNullOrWhiteSpace(value))
                {
                    throw new ArgumentException("empty value for --" + name);
                }
                values[name] = value;
            }
            string[] allowed = new string[]
            {
                "activate",
                "data-root",
                "mode",
                "runtime-folder",
                "url"
            };
            foreach (string key in values.Keys)
            {
                bool known = false;
                foreach (string candidate in allowed)
                {
                    if (String.Equals(key, candidate, StringComparison.OrdinalIgnoreCase))
                    {
                        known = true;
                        break;
                    }
                }
                if (!known)
                {
                    throw new ArgumentException("unknown option --" + key);
                }
            }
            return values;
        }

        private static string GetRequiredValue(Dictionary<string, string> values, string name)
        {
            string value = GetValue(values, name, null);
            if (String.IsNullOrWhiteSpace(value))
            {
                throw new ArgumentException("missing required option --" + name);
            }
            return value;
        }

        private static string GetValue(
            Dictionary<string, string> values,
            string name,
            string fallback
        )
        {
            string value;
            return values.TryGetValue(name, out value) ? value : fallback;
        }

        private static string RequireAbsolutePath(string value, string name)
        {
            if (!Path.IsPathRooted(value))
            {
                throw new ArgumentException(name + " must be an absolute path");
            }
            return Path.GetFullPath(value);
        }
    }
}
