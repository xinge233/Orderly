using System;

namespace Orderly.Desktop
{
    internal static class NavigationPolicy
    {
        public static bool Allows(Uri candidate, Uri origin)
        {
            return candidate != null
                && origin != null
                && String.Equals(
                    candidate.Scheme,
                    origin.Scheme,
                    StringComparison.OrdinalIgnoreCase
                )
                && String.Equals(candidate.Host, origin.Host, StringComparison.Ordinal)
                && candidate.Port == origin.Port;
        }

        public static bool IsHttpExternal(Uri candidate, Uri origin)
        {
            return candidate != null
                && (String.Equals(
                    candidate.Scheme,
                    Uri.UriSchemeHttp,
                    StringComparison.OrdinalIgnoreCase
                ) || String.Equals(
                    candidate.Scheme,
                    Uri.UriSchemeHttps,
                    StringComparison.OrdinalIgnoreCase
                ))
                && !Allows(candidate, origin);
        }

        public static bool AllowsPreview(Uri candidate, Uri origin)
        {
            return candidate != null
                && origin != null
                && String.Equals(
                    candidate.Scheme,
                    Uri.UriSchemeHttp,
                    StringComparison.OrdinalIgnoreCase
                )
                && String.Equals(
                    candidate.Host,
                    "127.0.0.1",
                    StringComparison.Ordinal
                )
                && candidate.Port > 0
                && candidate.Port != origin.Port
                && String.Equals(candidate.AbsolutePath, "/", StringComparison.Ordinal)
                && String.IsNullOrEmpty(candidate.UserInfo)
                && String.IsNullOrEmpty(candidate.Query)
                && String.IsNullOrEmpty(candidate.Fragment);
        }
    }
}
