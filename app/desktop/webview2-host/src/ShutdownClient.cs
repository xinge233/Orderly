using System;
using System.Net;
using System.Net.Http;
using System.Threading.Tasks;

namespace Orderly.Desktop
{
    internal enum ShutdownRequestResult
    {
        Accepted,
        ExecutionInProgress,
        Rejected,
        Unavailable
    }

    internal static class ShutdownClient
    {
        private static readonly HttpClient Client = CreateClient();

        public static async Task<ShutdownRequestResult> RequestAsync(Uri baseUri)
        {
            try
            {
                using (HttpRequestMessage request = new HttpRequestMessage(
                    HttpMethod.Post,
                    new Uri(baseUri, "api/system/shutdown")
                ))
                {
                    request.Headers.Add("X-Orderly-Action", "shutdown");
                    using (HttpResponseMessage response = await Client.SendAsync(request))
                    {
                        if (response.StatusCode == HttpStatusCode.Accepted
                            || response.StatusCode == HttpStatusCode.OK)
                        {
                            return ShutdownRequestResult.Accepted;
                        }
                        if (response.StatusCode == HttpStatusCode.Conflict)
                        {
                            return ShutdownRequestResult.ExecutionInProgress;
                        }
                        return ShutdownRequestResult.Rejected;
                    }
                }
            }
            catch (HttpRequestException)
            {
                return ShutdownRequestResult.Unavailable;
            }
            catch (TaskCanceledException)
            {
                return ShutdownRequestResult.Unavailable;
            }
        }

        public static async Task<bool> IsAliveAsync(Uri baseUri)
        {
            try
            {
                using (HttpResponseMessage response = await Client.GetAsync(
                    new Uri(baseUri, "api/health/live")
                ))
                {
                    return response.IsSuccessStatusCode;
                }
            }
            catch (HttpRequestException)
            {
                return false;
            }
            catch (TaskCanceledException)
            {
                return false;
            }
        }

        private static HttpClient CreateClient()
        {
            HttpClient client = new HttpClient();
            client.Timeout = TimeSpan.FromSeconds(5);
            return client;
        }
    }
}
