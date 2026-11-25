using System;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Windows.Forms;

namespace Orderly.Desktop
{
    internal sealed class WindowState
    {
        public int Width { get; set; }
        public int Height { get; set; }
        public int Left { get; set; }
        public int Top { get; set; }
        public bool Maximized { get; set; }
    }

    internal static class WindowStateStore
    {
        private const int DefaultWidth = 1440;
        private const int DefaultHeight = 900;
        private const int MinimumWidth = 960;
        private const int MinimumHeight = 640;

        public static WindowState Load(string dataRoot)
        {
            WindowState state = new WindowState();
            state.Width = DefaultWidth;
            state.Height = DefaultHeight;
            state.Left = Int32.MinValue;
            state.Top = Int32.MinValue;

            string path = GetPath(dataRoot);
            try
            {
                if (File.Exists(path))
                {
                    string[] parts = File.ReadAllText(path).Trim().Split('|');
                    if (parts.Length == 5)
                    {
                        state.Width = Math.Max(
                            MinimumWidth,
                            Int32.Parse(parts[0], CultureInfo.InvariantCulture)
                        );
                        state.Height = Math.Max(
                            MinimumHeight,
                            Int32.Parse(parts[1], CultureInfo.InvariantCulture)
                        );
                        state.Left = Int32.Parse(parts[2], CultureInfo.InvariantCulture);
                        state.Top = Int32.Parse(parts[3], CultureInfo.InvariantCulture);
                        state.Maximized = Boolean.Parse(parts[4]);
                    }
                }
            }
            catch
            {
                state.Width = DefaultWidth;
                state.Height = DefaultHeight;
                state.Left = Int32.MinValue;
                state.Top = Int32.MinValue;
                state.Maximized = false;
            }
            return state;
        }

        public static void Save(string dataRoot, Form form)
        {
            try
            {
                Rectangle bounds = form.WindowState == FormWindowState.Normal
                    ? form.Bounds
                    : form.RestoreBounds;
                string path = GetPath(dataRoot);
                Directory.CreateDirectory(Path.GetDirectoryName(path));
                string value = String.Join(
                    "|",
                    new string[]
                    {
                        Math.Max(MinimumWidth, bounds.Width).ToString(CultureInfo.InvariantCulture),
                        Math.Max(MinimumHeight, bounds.Height).ToString(CultureInfo.InvariantCulture),
                        bounds.Left.ToString(CultureInfo.InvariantCulture),
                        bounds.Top.ToString(CultureInfo.InvariantCulture),
                        (form.WindowState == FormWindowState.Maximized).ToString()
                    }
                );
                File.WriteAllText(path, value);
            }
            catch
            {
                // Window state persistence is optional.
            }
        }

        public static void Apply(Form form, WindowState state)
        {
            form.StartPosition = FormStartPosition.Manual;
            Rectangle bounds = new Rectangle(state.Left, state.Top, state.Width, state.Height);
            bool visible = false;
            foreach (Screen screen in Screen.AllScreens)
            {
                if (screen.WorkingArea.IntersectsWith(bounds))
                {
                    visible = true;
                    break;
                }
            }
            if (!visible)
            {
                form.StartPosition = FormStartPosition.CenterScreen;
                form.Size = new Size(state.Width, state.Height);
            }
            else
            {
                form.Bounds = bounds;
            }
            if (state.Maximized)
            {
                form.WindowState = FormWindowState.Maximized;
            }
        }

        private static string GetPath(string dataRoot)
        {
            return Path.Combine(dataRoot, "desktop-window.txt");
        }
    }
}
