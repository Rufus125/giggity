package net.gaast.giggity;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import android.view.View;

import org.apache.commons.io.output.NullOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.util.AbstractList;
import java.util.Collection;
import java.util.LinkedList;

public class ScheduleUI extends Schedule {
	/* Schedule subclass which should carry, among other things, elements that depend on Android.
	 * The Base class should be plain-ish Java so that I can use it externally as well. This is
	 * in fact not just UI stuff but also for example database stuff (user reminders/deletions/etc
	 * persistence) */

	public Giggity app;
	public Db.Connection db;

	private Handler progressHandler;

	private ScheduleUI(Giggity ctx) {
		app = ctx;
	}

	public static ScheduleUI loadSchedule(Giggity ctx, String url, Fetcher.Source source, Handler progressHandler) throws LoadException {
		ScheduleUI ret = new ScheduleUI(ctx);
		ret.progressHandler = progressHandler;

		if (ret.progressHandler == null) {
			// TODO: Test this uncommon codepath to ensure noop handlers are noop and not crashop.
			// (Can't test this now because it looks like reminders are kinda broken... :< )
			ret.progressHandler = new Handler();
		}

		Db.DbSchedule ds = ctx.getDb().getSchedule(url);
		if (ds != null) {
			String tz = ds.getTimezone();
			if (tz != null && !tz.isEmpty()) {
				ret.setInTZ(ZoneId.of(tz));
			}
		}

		Fetcher f = null;

		try {
			f = ctx.fetch(url, source);
			f.setProgressHandler(ret.progressHandler);
			if (f.fromCache()) {
				// Disable the "load from cache" button since we're doing that already. */
				ret.progressHandler.sendEmptyMessage(ScheduleViewActivity.LoadProgress.FROM_CACHE);
			}
			ret.loadSchedule(f.getReader(), url);
		} catch (LoadException | IOException e) {
			Log.e("Schedule.loadSchedule", "Exception while downloading schedule: " + e);
			e.printStackTrace();
			throw new LoadException("Network I/O problem: " + e);
		}
		f.keep();

		// Disable the "fall back to cache" button at this stage if it's even shown, since we're
		// nearly done, only need to apply user/dynamic data.
		ret.progressHandler.sendEmptyMessage(ScheduleViewActivity.LoadProgress.STATIC_DONE);
		if (ret.app.hasSchedule(url)) {
			// TODO: Theoretically, online could still win the race over cached..
			throw new Schedule.LateException();
		}

		ret.db = ret.app.getDb();
		ret.db.setSchedule(ret, url, f.isFresh());
		String md_json = ret.db.getMetadata();
		if (md_json != null) {
			ret.addMetadata(md_json);
		}

		/* From now, changes should be marked to go back into the db. */
		ret.fullyLoaded = true;

		return ret;
	}

	public String getString(int id) {
		return app.getString(id);
	}

	public void setProgressHandler(Handler handler) {
		progressHandler = handler;
	}

	/** Would like to kill this, but still used for remembering currently
	 * viewed day for a schedule. */
	public Db.Connection getDb() {
		return db;
	}

	private InputStream getIconStream() {
		if (getIconUrl() == null || getIconUrl().isEmpty()) {
			return null;
		}

		try {
			Fetcher f = new Fetcher(app, getIconUrl(), Fetcher.Source.CACHE_ONLY);
			return f.getStream();
		} catch (IOException e) {
			// This probably means it's not in cache. :-( So we'll fetch it in the background and
			// will hopefully succeed on the next call.
		}
		/* For fetching the icon file in the background. */
		Thread iconFetcher = new Thread() {
			@Override
			public void run() {
				Fetcher f;
				try {
					f = new Fetcher(app, getIconUrl(), Fetcher.Source.DEFAULT);
					// Just feed it to /dev/null so that next time we can CACHE_ONLY fetch it.
					// It won't get cached without completing this bogus read!
					Giggity.copy(f.getStream(), new NullOutputStream());
				} catch (IOException e) {
					Log.e("getIconStream", "Fetch error: " + e);
					return;
				}
				f.keep();
			}
		};
		iconFetcher.start();
		return null;
	}

	public Bitmap getIconBitmap() {
		InputStream stream = getIconStream();
		Bitmap ret = null;
		if (stream != null) {
			ret = BitmapFactory.decodeStream(stream);
			if (ret == null) {
				Log.w("getIconBitmap", "Discarding unparseable file");
				return null;
			}
			if (ret.getHeight() > 512 || ret.getHeight() != ret.getWidth()) {
				Log.w("getIconBitmap", "Discarding, icon not square or >512 pixels");
				return null;
			}
			if (!ret.hasAlpha()) {
				Log.w("getIconBitmap", "Discarding, no alpha layer");
				return null;
			}
		}
		return ret;
	}

	/* Returns true if any of the statuses has changed. */
	public boolean updateRoomStatus() {
		try {
			Fetcher f = new Fetcher(app, roomStatusUrl, Fetcher.Source.DEFAULT);
			return updateRoomStatus(f.slurp());
		} catch (IOException e) {
			Log.d("updateRoomStatus", "Fetch setup failure");
			e.printStackTrace();
			return false;
		}
	}

	protected void applyItem(Item item) {
		if (fullyLoaded) {
			db.saveScheduleItem(item);
		}
		app.updateRemind(item);
	}

	public void initSearch() {
		db.resetIndex(allItems.values());
	}

	public AbstractList<Item> searchItems(String q_) {
		Collection<String> ids = db.searchItems(q_);
		if (ids == null) {
			return null;
		}
		LinkedList<Item> ret = new LinkedList<Item>();
		Log.d("searchItems", "" + ids.size() + " items");
		for (String id : ids) {
//			Log.d("searchItems", "id=" + id + " " + allItems.containsKey(id));
			ret.add(allItems.get(id));
		}
		return ret;
	}

	/** Click-listener to open geo: URL belonging to a room. */
	public static View.OnClickListener locationClickListener(final Context ctx, final Schedule.Line line) {
		return new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Uri uri = Uri.parse(line.getLocation());
				Intent geoi = new Intent(Intent.ACTION_VIEW, uri);
				ctx.startActivity(geoi);
			}
		};
	}
}
