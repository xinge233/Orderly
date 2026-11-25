using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Threading;

namespace Orderly.Desktop
{
    internal sealed class HostActivation : IDisposable
    {
        private readonly Mutex mutex;
        private readonly EventWaitHandle activationEvent;
        private readonly EventWaitHandle stopEvent;
        private readonly Thread listener;
        private bool disposed;

        private HostActivation(Mutex mutex, EventWaitHandle activationEvent)
        {
            this.mutex = mutex;
            this.activationEvent = activationEvent;
            this.stopEvent = new EventWaitHandle(false, EventResetMode.ManualReset);
            this.listener = new Thread(Listen);
            this.listener.IsBackground = true;
            this.listener.Name = "OrderlyWebView2Activation";
        }

        public event Action Activated;

        public static bool TryAcquire(string dataRoot, out HostActivation activation)
        {
            activation = null;
            string suffix = BuildSuffix(dataRoot);
            bool created;
            Mutex mutex = new Mutex(true, "Local\\OrderlyWebView2Host." + suffix, out created);
            if (!created)
            {
                mutex.Dispose();
                return false;
            }

            bool eventCreated;
            EventWaitHandle activationEvent = new EventWaitHandle(
                false,
                EventResetMode.AutoReset,
                "Local\\OrderlyWebView2Activate." + suffix,
                out eventCreated
            );
            activation = new HostActivation(mutex, activationEvent);
            return true;
        }

        public static bool ActivateRunningInstance(string dataRoot)
        {
            try
            {
                EventWaitHandle handle = EventWaitHandle.OpenExisting(
                    "Local\\OrderlyWebView2Activate." + BuildSuffix(dataRoot)
                );
                using (handle)
                {
                    return handle.Set();
                }
            }
            catch (WaitHandleCannotBeOpenedException)
            {
                return false;
            }
            catch (UnauthorizedAccessException)
            {
                return false;
            }
        }

        public static string BuildSuffix(string dataRoot)
        {
            string normalized = Path.GetFullPath(dataRoot)
                .TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
                .ToLowerInvariant();
            using (SHA256 sha256 = SHA256.Create())
            {
                byte[] hash = sha256.ComputeHash(Encoding.UTF8.GetBytes(normalized));
                StringBuilder builder = new StringBuilder(20);
                for (int index = 0; index < 10; index++)
                {
                    builder.Append(hash[index].ToString("x2"));
                }
                return builder.ToString();
            }
        }

        public void Start()
        {
            listener.Start();
        }

        public void Dispose()
        {
            if (disposed)
            {
                return;
            }
            disposed = true;
            stopEvent.Set();
            if (listener.IsAlive)
            {
                listener.Join(1000);
            }
            activationEvent.Dispose();
            stopEvent.Dispose();
            try
            {
                mutex.ReleaseMutex();
            }
            catch (ApplicationException)
            {
            }
            mutex.Dispose();
        }

        private void Listen()
        {
            while (!stopEvent.WaitOne(0))
            {
                if (activationEvent.WaitOne(250))
                {
                    Action handler = Activated;
                    if (handler != null)
                    {
                        handler();
                    }
                }
            }
        }
    }
}
