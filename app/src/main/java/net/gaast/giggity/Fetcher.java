package net.gaast.giggity;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.http.HttpResponseCache;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Caching HTTP fetcher. */
public class Fetcher {
	private Giggity app;
	private HttpURLConnection dlc;
	private Handler progressHandler;
	private long flen;
	private String type;
	private boolean isFresh_, fromCache_;

	private InputStream inStream;
	private BufferedReader inReader;
	static HttpResponseCache cache;

	public enum Source {
		REFRESH,           /* Ctrl-R, y'know */
		DEFAULT,           /* Check online (304 -> cache, and fail if we're offline). */
		CACHE_1D,          /* Get from cache but refresh once a day. */
		CACHE,             /* Get from cache, allow fetch if not available. (Will fetch ~yearly actually) */
		CACHE_ONLY,        /* Get from cache or fail. */
		CACHE_IF_OFFLINE,  /* Check online if we're not offline, otherwise use cache. */
	}

	public static boolean init(Context ctx) {
		File dir = new File(ctx.getCacheDir(), "downloads");
		if (dir.exists() && dir.isDirectory()) {
			Log.i("Fetcher", "Deleting old-style Fetcher cache");
			for (File f : dir.listFiles()) {
				if (!f.delete()) {

				}
			}
			dir.delete();
		}

		try {
			dir = new File(ctx.getCacheDir(), "HttpResponseCache");
			// 32 MiB. No clue what I need, would've preferred something time-based but no such
			// option. But let's allow for some large PDFs or something without evicting schedules?
			cache = HttpResponseCache.install(dir, 32 * 1024 * 1024);
		} catch (IOException e) {
			Log.w("Fetcher", "HttpResponseCache.install: " + e);
			return false;
		}
		return true;
	}

	public Fetcher(Giggity app_, String url, Source source) throws IOException {
		app = app_;

		Log.d("Fetcher", "Creating fetcher for " + url + " source=" + source);

		NetworkInfo network = ((ConnectivityManager)
				app.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
		// Probably not quite as relevant now as when I first wrote Giggity.. Docs recommend to use
		// a listener instead but meh, don't care much + suddenly reloading when connectivity comes
		// back would be annoying UX as well, not worth it.
		boolean online = (network == null) || !network.isConnected();

		URL dl = new URL(url);
		dlc = (HttpURLConnection) dl.openConnection();
		dlc.setInstanceFollowRedirects(true);
		dlc.addRequestProperty("Accept-Encoding", "gzip");
		dlc.addRequestProperty("User-Agent", "Giggity/" + BuildConfig.VERSION_NAME);

		if (source != Source.CACHE_ONLY && Looper.myLooper() == Looper.getMainLooper()) {
			throw new IOException("Fetcher: Should only allow Source.CACHE_ONLY in UI thread!");
		}
		if (cache == null || HttpResponseCache.getInstalled() != cache) {
			Log.w("Fetcher", "Cache appears to be missing?");
		}

		if (source == Source.CACHE_IF_OFFLINE) {
			if (!online) {
				source = Source.CACHE;
			} else {
				source = Source.DEFAULT;
			}
		}
		switch (source) {
			case DEFAULT:
				// No special headers needed, that's what default is about.. :)
				break;
			case REFRESH:
				dlc.addRequestProperty("Cache-Control", "max-age=0");
				break;
			case CACHE_1D:
				// Rely on cache with daily refreshes, regardless of what the server said.
				// (Would be nice to still trust the server if it returned a higher max-age but how?)
				dlc.addRequestProperty("Cache-Control", "max-age=" + (24 * 60 * 60));  // 1d
				break;
			case CACHE:
				// Add 1y to whatever the server said. Practically rely on cache forever.
				dlc.addRequestProperty("Cache-Control", "max-stale=" + (365 * 24 * 60 * 60));  // 1y
				break;
			case CACHE_ONLY:
				dlc.addRequestProperty("Cache-Control", "only-if-cached");
				// 10y (though apparently I could just omit the argument?)
				dlc.addRequestProperty("Cache-Control", "max-stale=" + (3650 * 24 * 60 * 60));
				break;
		}

		String status = dlc.getResponseCode() + " " + dlc.getResponseMessage();
		Log.d("Fetcher", "HTTP status " + status);
		// Log.d("Fetcher", "Req " + cache.getRequestCount() + " Net " + cache.getNetworkCount() + " Hit " + cache.getHitCount());
		String loc = dlc.getHeaderField("Location");
		if (loc != null) {
			Log.d("http-location", loc);
		}

		fromCache_ = false;
		isFresh_ = true;
		for (Map.Entry<String, java.util.List<String>> hd : dlc.getHeaderFields().entrySet()) {
			for (String v : hd.getValue()) {
//				Log.d("Fetcher", "" + hd.getKey() + ": " + v);
				if (hd.getKey() == null) {
				} else if (hd.getKey().equals("Warning") && v.contains("Response is stale")) {
					isFresh_ = false;
				} else if (hd.getKey().equals("X-Android-Response-Source") && v.contains("CACHE")) {
					fromCache_ = true;
				}
			}
		}
		Log.d("Fetcher", "fromCache=" + fromCache_ + " isFresh=" + isFresh_);

		if (dlc.getResponseCode() == HttpURLConnection.HTTP_OK) {
			inStream = dlc.getInputStream();

			// Set progress bar "goal" if we know it.
			if (dlc.getContentLength() > -1) {
				flen = dlc.getContentLength();
				inStream = new ProgressStream(inStream);
			}

			String enc = dlc.getContentEncoding();
			if (enc != null && enc.contains("gzip")) {
				inStream = new GZIPInputStream(inStream);
			}
		} else {
			throw new IOException("Download error: HTTP " + status);
		}
	}

	public void setProgressHandler(Handler handler) {
		progressHandler = handler;
	}

	// Export InputStream.
	public InputStream getStream() {
		if (inStream == null) {
			throw new RuntimeException("Already converted to BufferedReader!");
		}
		return inStream;
	}

	// Create a BufferedReader assuming charset=utf8 if necessary and return it. Streamer interface
	// should no longer be used after that.
	public BufferedReader getReader() {
		if (inReader == null) {
			inReader = new BufferedReader(new InputStreamReader(inStream, StandardCharsets.UTF_8));
			inStream = null;
		}
		return inReader;
	}

	/** Convenience slurper to just grab the whole file *as a String*. NO BINARIES! */
	public String slurp() throws IOException {
		String ret = "";
		char[] buf = new char[1024];
		int n;

		// Just to ensure inReader exists.
		getReader();
		while ((n = inReader.read(buf, 0, buf.length)) > 0)
			ret += new String(buf, 0, n);

		return ret;
	}

	public boolean fromCache() {
		return fromCache_;
	}

	public boolean isFresh() {
		return isFresh_;
	}

	/** Used to be mandatory in old implementation, vs cancel() to delete corrupt data (and keep
	 *  previous copy). Sadly we have lost that level now but I don't think it was used much anyway.
	 *  Just keep this as a cache flush checkpoint. */
	public void keep() {
		cache.flush();
	}

	/** Intermediate streamer to report progress to caller (mostly for UI). */
	private class ProgressStream extends InputStream {
		private InputStream in;
		private boolean waiting;
		private long offset;

		public ProgressStream(InputStream in_) {
			in = in_;
		}
		
		@Override
		public int read() throws IOException {
			if (!waiting)
				offset++;
			return in.read();
		}
		
		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			int ret = in.read(b, off, len);
			if (!waiting) {
				offset += ret;
				if (progressHandler != null && flen > 0 && ret > 0) {
					int prog = (int) (100L * offset / flen);
					if (prog > 0)
						progressHandler.sendEmptyMessage((int) (100L * offset / flen));
				}
			}
			return ret;
		}
		
		@Override
		public int read(byte[] b) throws IOException {
			return read(b, 0, b.length);
		}
		
		@Override
		public void mark(int limit) {
			in.mark(limit);
			waiting = true;
		}
		
		@Override
		public void reset() throws IOException {
			in.reset();
			waiting = false;
		}
	}
}
