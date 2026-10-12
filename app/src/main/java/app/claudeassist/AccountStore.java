package app.claudeassist;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the list of accounts, which one is active, and whether each one is
 * restricted. Also holds per-account cookie snapshots, which are only used on
 * devices whose WebView has no multi-profile support.
 */
public class AccountStore {

    public static class Account {
        public final int id;
        public String name;
        public boolean restricted;

        Account(int id, String name, boolean restricted) {
            this.id = id;
            this.name = name;
            this.restricted = restricted;
        }

        /** Name of the WebView profile that holds this account's cookies and storage. */
        public String profileName() {
            return "acct_" + id;
        }
    }

    private static final String PREFS = "claude_accounts";
    private static final String KEY_LIST = "list";
    private static final String KEY_CURRENT = "current";
    private static final String KEY_NEXT_ID = "next_id";
    private static final String KEY_COOKIES = "cookies_";

    private final SharedPreferences prefs;
    private final List<Account> accounts = new ArrayList<>();
    private int currentId;
    private int nextId;

    public AccountStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    private void load() {
        accounts.clear();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_LIST, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                accounts.add(new Account(o.getInt("id"), o.getString("name"), o.optBoolean("restricted", false)));
            }
        } catch (JSONException ignored) {
            accounts.clear();
        }
        nextId = prefs.getInt(KEY_NEXT_ID, 0);
        for (Account a : accounts) nextId = Math.max(nextId, a.id + 1);
        if (accounts.isEmpty()) {
            accounts.add(new Account(nextId++, "Account 1", false)); // unrestricted by default
            currentId = accounts.get(0).id;
            persist();
        } else {
            currentId = prefs.getInt(KEY_CURRENT, accounts.get(0).id);
            if (find(currentId) == null) currentId = accounts.get(0).id;
        }
    }

    private void persist() {
        JSONArray array = new JSONArray();
        try {
            for (Account a : accounts) {
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("name", a.name);
                o.put("restricted", a.restricted);
                array.put(o);
            }
        } catch (JSONException ignored) {
        }
        prefs.edit()
                .putString(KEY_LIST, array.toString())
                .putInt(KEY_CURRENT, currentId)
                .putInt(KEY_NEXT_ID, nextId)
                .apply();
    }

    public List<Account> list() {
        return new ArrayList<>(accounts);
    }

    public Account find(int id) {
        for (Account a : accounts) if (a.id == id) return a;
        return null;
    }

    public Account current() {
        return find(currentId);
    }

    public int currentId() {
        return currentId;
    }

    public void setCurrent(int id) {
        if (find(id) == null) return;
        currentId = id;
        persist();
    }

    public Account add(String name) {
        Account a = new Account(nextId++, name, false);
        accounts.add(a);
        persist();
        return a;
    }

    public void rename(int id, String name) {
        Account a = find(id);
        if (a == null) return;
        a.name = name;
        persist();
    }

    public void setRestricted(int id, boolean restricted) {
        Account a = find(id);
        if (a == null) return;
        a.restricted = restricted;
        persist();
    }

    /** Removes the account. The last remaining account cannot be removed. */
    public boolean remove(int id) {
        Account a = find(id);
        if (a == null || accounts.size() <= 1) return false;
        accounts.remove(a);
        if (currentId == id) currentId = accounts.get(0).id;
        prefs.edit().remove(KEY_COOKIES + id).apply();
        persist();
        return true;
    }

    // ---- cookie snapshots (fallback mode only) ----

    public void saveCookies(int id, Map<String, String> cookiesByUrl) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, String> e : cookiesByUrl.entrySet()) {
                if (e.getValue() != null) o.put(e.getKey(), e.getValue());
            }
        } catch (JSONException ignored) {
        }
        prefs.edit().putString(KEY_COOKIES + id, o.toString()).apply();
    }

    public Map<String, String> loadCookies(int id) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(prefs.getString(KEY_COOKIES + id, "{}"));
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String url = keys.next();
                result.put(url, o.getString(url));
            }
        } catch (JSONException ignored) {
        }
        return result;
    }

    public void clearCookies(int id) {
        prefs.edit().remove(KEY_COOKIES + id).apply();
    }
}
