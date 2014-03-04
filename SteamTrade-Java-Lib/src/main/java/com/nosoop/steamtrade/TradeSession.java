package com.nosoop.steamtrade;

import bundled.steamtrade.org.json.JSONObject;
import bundled.steamtrade.org.json.JSONException;
import com.nosoop.steamtrade.status.*;
import com.nosoop.steamtrade.TradeListener.TradeStatusCodes;
import com.nosoop.steamtrade.inventory.*;
import com.nosoop.steamtrade.status.TradeEvent.TradeAction;
import java.io.UnsupportedEncodingException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a session of a trade.
 *
 * @author Top-Cat, nosoop
 */
public class TradeSession implements Runnable {

    // Static properties
    public final static String STEAM_COMMUNITY_DOMAIN = "steamcommunity.com";
    public final static String STEAM_TRADE_URL = "http://steamcommunity.com/trade/%s/";
    // Generic Trade info
    public boolean meReady = false, otherReady = false;
    int lastEvent = 0;
    // Fixed object to synchronize polling against.
    protected final Object POLL_LOCK = new Object();
    //
    // The items put up for offer.
    // TODO Make this my/other stuff into a struct.
    public Set<TradeInternalItem> myTradeOffer = new HashSet<>(), otherTradeOffer = new HashSet<>();
    public Object[] trades;
    //
    // The inventories of both users.
    public TradeInternalInventories otherUserTradeInventories, myTradeInventories;
    public List<AppContextPair> myAppContextData;
    //
    // Trade interfacing object.
    private final TradeCommands API;
    //
    // Strings needed for Steam API.
    private final String TRADE_URL;
    private final String STEAM_LOGIN;
    private final String SESSION_ID;
    protected int version = 1;
    protected int logpos;
    protected int numEvents;
    //
    // The trade listener to offload events to.
    private TradeListener tradeListener;
    //
    // Timing variables.
    private long TIME_TRADE_START, timeLastPartnerAction;
    private final long STEAMID_SELF, STEAMID_PARTNER;

    /**
     * Starts a new trading session.
     *
     * @param steamidSelf Long representation of our own SteamID.
     * @param steamidPartner Long representation of our trading partner's
     * SteamID.
     * @param sessionId String value of the Base64-encoded session token.
     * @param token String value of Steam's login token.
     * @param listener Trade listener to respond to trade actions.
     */
    @SuppressWarnings("LeakingThisInConstructor")
    public TradeSession(long steamidSelf, long steamidPartner, String sessionId, String token, TradeListener listener) {
        STEAMID_SELF = steamidSelf;
        STEAMID_PARTNER = steamidPartner;

        trades = new Object[]{myTradeOffer, otherTradeOffer};

        SESSION_ID = sessionId;
        STEAM_LOGIN = token;

        listener.trade = this;
        tradeListener = listener;

        TRADE_URL = String.format(TradeSession.STEAM_TRADE_URL, STEAMID_PARTNER);

        API = new TradeCommands(TRADE_URL, SESSION_ID, STEAM_LOGIN);

        myTradeInventories = new TradeInternalInventories();
        otherUserTradeInventories = new TradeInternalInventories();

        tradeListener.onWelcome();
        scrapeBackpackContexts();

        tradeListener.onAfterInit();
        
        timeLastPartnerAction = TIME_TRADE_START = System.currentTimeMillis();
    }
    public Status status = null;

    /**
     * Polls the TradeSession for updates. Suggested poll rate is once every
     * second.
     */
    @SuppressWarnings("unchecked")
    @Override
    public void run() {
        synchronized (POLL_LOCK) {
            try {
                status = getStatus();
            } catch (final JSONException e) {
                tradeListener.onError(
                        TradeStatusCodes.STATUS_PARSE_ERROR, e.getMessage());

                try {
                    API.cancelTrade();
                } catch (JSONException ex) {
                    ex.printStackTrace();
                }
                tradeListener.onTradeClosed();
            }

            // Update version
            if (status.newversion) {
                version = status.version;
            }

            if (lastEvent < status.events.size()) {
                // Process all new, unhandled events.
                for (; lastEvent < status.events.size(); lastEvent++) {
                    handleTradeEvent(status.events.get(lastEvent));
                }
            } else {
                // If there was no new action during this poll, update timer.
                final long timeCurrent = System.currentTimeMillis();

                final int secondsSinceLastAction = (int) ((timeCurrent - timeLastPartnerAction) / 1000);
                final int secondsSinceTradeStart = (int) ((timeCurrent - TIME_TRADE_START) / 1000);

                tradeListener.onTimer(secondsSinceLastAction, secondsSinceTradeStart);
            }

            if (status.trade_status == TradeStatusCodes.TRADE_COMPLETED) {
                // Trade successful.
                tradeListener.onTradeSuccess();
                tradeListener.onTradeClosed();
            } else if (status.trade_status == 
                    TradeStatusCodes.STATUS_ERRORMESSAGE) {
                tradeListener.onError(status.trade_status, status.error);
                tradeListener.onTradeClosed();
            } else if (status.trade_status > 1) {
                // Refer to TradeListener.TradeStatusCodes for known values.
                tradeListener.onError(status.trade_status, null);
                tradeListener.onTradeClosed();
            }

            // Update Local Variables
            if (status.them != null) {
                otherReady = status.them.ready;
                meReady = status.me.ready;
            }

            // Update version
            if (status.newversion) {
                tradeListener.onNewVersion();
            }

            if (status.logpos != 0) {
                // ... no idea.
                // DebugPrint.println("WAT");
                logpos = status.logpos;
            }
        }
    }

    /**
     * Handles received trade events and fires the appropriate event at the
     * given TradeListener, defined in the constructor.
     *
     * @param evt Trade event being handled.
     */
    private void handleTradeEvent(final TradeEvent evt) {
        // Drop the event if the event's steamid is not theirs.
        boolean isBot = !evt.steamid.equals(String.valueOf(STEAMID_PARTNER));

        // TODO Link their asset to variable item count.
        if (status.them.assets != null) {
            //System.out.println(Arrays.toString(status.them.assets.toArray()));
        }

        switch (evt.action) {
            case TradeAction.ITEM_ADDED:
                eventUserAddedItem(evt);
                break;
            case TradeAction.ITEM_REMOVED:
                eventUserRemovedItem(evt);
                break;
            case TradeAction.READY_TOGGLED:
                if (!isBot) {
                    otherReady = true;
                    tradeListener.onUserSetReadyState(true);
                } else {
                    meReady = true;
                }
                break;
            case TradeAction.READY_UNTOGGLED:
                if (!isBot) {
                    otherReady = false;
                    tradeListener.onUserSetReadyState(false);
                } else {
                    meReady = false;
                }
                break;
            case 4:
                if (!isBot) {
                    tradeListener.onUserAccept();
                }
                break;
            case TradeAction.MESSAGE_ADDED:
                if (!isBot) {
                    tradeListener.onMessage(evt.text);
                }
                break;
            case 6:
            // TODO Add support for currency.
            case 8:
            // TODO Add support for stackable items.
            default:
                // Let the trade listener handle it.
                tradeListener.onUnknownAction(evt);
                break;
        }

        if (!isBot) {
            timeLastPartnerAction = System.currentTimeMillis();
        }
    }

    private void eventUserAddedItem(TradeEvent evt) {
        boolean isBot = !evt.steamid.equals(String.valueOf(STEAMID_PARTNER));

        if (!isBot) {
            /**
             * If this is the other user and we don't have their inventory yet,
             * then we will load it.
             */
            if (!otherUserTradeInventories.hasInventory(evt.appid, evt.contextid)) {
                addForeignInventory(evt.appid, evt.contextid);
            }
            final TradeInternalItem item = otherUserTradeInventories.getInventory(evt.appid, evt.contextid).getItem(evt.assetid);
            tradeListener.onUserAddItem(item);
        }

        // Add to internal tracking.
        final TradeInternalInventories inv = isBot
                ? myTradeInventories : otherUserTradeInventories;

        final TradeInternalItem item =
                inv.getInventory(evt.appid, evt.contextid).getItem(evt.assetid);

        ((Set<TradeInternalItem>) trades[isBot ? 0 : 1]).add(item);
    }

    private void eventUserRemovedItem(TradeEvent evt) {
        boolean isBot = !evt.steamid.equals(String.valueOf(STEAMID_PARTNER));
        ((Set<Long>) trades[isBot ? 0 : 1]).remove(evt.assetid);
        if (!isBot) {
            final TradeInternalItem item = otherUserTradeInventories.getInventory(evt.appid, evt.contextid).getItem(evt.assetid);
            tradeListener.onUserRemoveItem(item);
        }

        // Get the item from one of our inventories and remove.
        final TradeInternalItem item =
                (isBot ? myTradeInventories : otherUserTradeInventories)
                .getInventory(evt.appid, evt.contextid).getItem(evt.assetid);

        ((Set<TradeInternalItem>) trades[isBot ? 0 : 1]).remove(item);
    }

    private void eventUserSetCurrencyAmount(TradeEvent evt) {
        boolean isBot = !evt.steamid.equals(String.valueOf(STEAMID_PARTNER));
        // TODO Set support for currency?
        if (!isBot) {
            if (!otherUserTradeInventories.hasInventory(evt.appid, evt.contextid)) {
                addForeignInventory(evt.appid, evt.contextid);
            }
            final TradeInternalCurrency item = otherUserTradeInventories.getInventory(evt.appid, evt.contextid).getCurrency(evt.currencyid);

            // TODO Fire event at listener for currency update.
        }
    }

    /**
     * Loads a copy of the trade screen, passing the data to ContextScraper to
     * generate a list of AppContextPairs to load our inventories with.
     */
    private void scrapeBackpackContexts() {
        // I guess we're scraping the trade page.
        final Map<String, String> data = new HashMap<>();

        String pageData = API.fetch(TRADE_URL, "GET", data);

        try {
            List<AppContextPair> contexts = 
                    ContextScraper.scrapeContextData(pageData);
            myAppContextData = contexts;
        } catch (JSONException e) {
            // Notify the trade listener if we can't get our backpack data.
            
            myAppContextData = new ArrayList<>();
            tradeListener.onError(TradeStatusCodes.BACKPACK_SCRAPE_ERROR,
                    null);
            
        }
    }

    /**
     * Fetches updates to the current trade.
     *
     * @return Status object to be processed.
     * @throws JSONException Malformed / invalid response data.
     */
    private Status getStatus() throws JSONException {
        final Map<String, String> data = new HashMap<>();
        try {
            data.put("sessionid", URLDecoder.decode(SESSION_ID, "UTF-8"));
        } catch (final UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        data.put("logpos", "" + logpos);
        data.put("version", "" + version);

        final String response = API.fetch(TRADE_URL + "tradestatus/", "POST", data);

        return new Status(new JSONObject(response));
    }

    /**
     * Loads one of our game inventories, storing it in a
     * TradeInternalInventories object.
     *
     * @param appContext An AppContextPair representing the inventory to be
     * loaded.
     */
    public void loadOwnInventory(AppContextPair appContext) {
        final String url, response;

        if (myTradeInventories.hasInventory(appContext)) {
            return;
        }

        url = String.format("http://steamcommunity.com/profiles/%d/inventory/json/%d/%d/?trading=1", STEAMID_SELF, appContext.getAppid(), appContext.getContextid());

        response = API.fetch(url, "GET", null, true);

        myTradeInventories.addInventory(appContext, response);
    }

    /**
     * Loads a copy of the other person's possibly private inventory, once we
     * receive an item from it.

     * @param appid The game to load the inventory from.
     * @param contextid The inventory of the game to be loaded.
     */
    protected synchronized void addForeignInventory(int appid, long contextid) {
        /**
         * TODO Make the loading concurrent so it does not hang on large
         * inventories. ... I'm looking at you, backpack.tf card swap bots. Not
         * that that's a bad thing - it's just not a good thing.
         */
        final Map<String, String> data = new HashMap<>();

        try {
            data.put("sessionid", URLDecoder.decode(SESSION_ID, "UTF-8"));
        } catch (final UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        data.put("steamid", STEAMID_PARTNER + "");
        data.put("appid", appid + "");
        data.put("contextid", contextid + "");

        String feed = API.fetch(TRADE_URL + "foreigninventory", "POST", data);

        otherUserTradeInventories.addInventory(appid, contextid, feed);
    }

    public long getOwnSteamId() {
        return STEAMID_SELF;
    }
    
    public long getPartnerSteamId() {
        return STEAMID_PARTNER;
    }
    
    /**
     * Gets the commands associated with this trade session.
     *
     * @return TradeCommands object that handles the user-trade actions.
     */
    public TradeCommands getCmds() {
        return API;
    }

    /**
     * A utility class to hold all web-based 'fetch' actions when dealing with
     * Steam Trade in the current trading session.
     *
     * @author nosoop
     */
    public class TradeCommands {

        final String TRADE_URL;
        final String SESSION_ID;
        final String STEAM_LOGIN;

        TradeCommands(String baseTradeURL, String sessionId, String steamLogin) {
            TRADE_URL = baseTradeURL;
            SESSION_ID = sessionId;
            STEAM_LOGIN = steamLogin;
        }

        /**
         * Adds an item to the trade.
         *
         * @param item The item, represented by an TradeInternalItem instance.
         * @param slot The offer slot to place the item in (0~255).
         */
        public void addItem(TradeInternalItem item, int slot) {
            addItem(item.appid, item.contextid, item.assetid, slot);
        }

        /**
         * Adds an item to the trade manually.
         *
         * @param appid The game inventory for the item.
         * @param contextid The inventory "context" for the item.
         * @param assetid The inventory "asset", the item id.
         * @param slot The offer slot to place the item in (0~255).
         */
        public void addItem(int appid, long contextid, long assetid, int slot) {
            final Map<String, String> data = new HashMap<>();

            try {
                data.put("sessionid", URLDecoder.decode(SESSION_ID, "UTF-8"));
            } catch (final UnsupportedEncodingException e) {
                e.printStackTrace();
            }

            data.put("appid", "" + appid);
            data.put("contextid", "" + contextid);
            data.put("itemid", "" + assetid);
            data.put("slot", "" + slot);
            fetch(TRADE_URL + "additem", "POST", data);
        }

        /**
         * Removes an item from the trade.
         *
         * @param item The item, represented by an TradeInternalItem instance.
         */
        public void removeItem(TradeInternalItem item) {
            removeItem(item.appid, item.contextid, item.assetid);
        }

        /**
         * Removes an item from the trade manually.
         *
         * @param appid The game inventory for the item.
         * @param contextid The inventory "context" for the item.
         * @param assetid The inventory "asset", the item id.
         */
        public void removeItem(int appid, long contextid, long assetid) {
            final Map<String, String> data = new HashMap<>();

            try {
                data.put("sessionid", URLDecoder.decode(SESSION_ID, "UTF-8"));
            } catch (final UnsupportedEncodingException e) {
                e.printStackTrace();
            }

            data.put("appid", "" + appid);
            data.put("contextid", "" + contextid);
            data.put("itemid", "" + assetid);
            fetch(TRADE_URL + "removeitem", "POST", data);
        }

        /**
         * Tick / untick the checkbox signaling that we are ready to complete
         * the trade.
         *
         * @param ready Whether the client is ready to trade or not
         * @return True on success, false otherwise.
         */
        public boolean setReady(boolean ready) {
            final Map<String, String> data = new HashMap<>();
            try {
                data.put("sessionid", URLDecoder.decode(SESSION_ID, "UTF-8"));
            } catch (final UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            data.put("ready", ready ? "true" : "false");
            data.put("version", "" + version);
            final String response = fetch(TRADE_URL + "toggleready", "POST", data);
            try {
                Status readyStatus = new Status(new JSONObject(response));
                if (readyStatus.success) {
                    if (readyStatus.trade_status == 0) {
                        otherReady = readyStatus.them.ready;
                        meReady = readyStatus.me.ready;
                    } else {
                        meReady = true;
                    }
                    return meReady;
                }
            } catch (final JSONException e) {
                e.printStackTrace();
            }
            return false;
        }

        /**
         * Hits the "Make Trade" button, finalizing the trade. Not sure what the
         * response is for.
         *
         * @return JSONObject representing trade status.
         * @throws JSONException if the response is unexpected.
         */
        public JSONObject acceptTrade() throws JSONException {
            final Map<String, String> data = new HashMap<>();
            try {
                data.put("sessionid", URLDecoder.decode(SESSION_ID, "UTF-8"));
            } catch (final UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            data.put("version", "" + version);
            final String response = fetch(TRADE_URL + "confirm", "POST", data);

            return new JSONObject(response);
        }

        /**
         * Cancels the trade session as if we clicked the "Cancel Trade" button.
         * Expect a call of onError(TradeErrorCodes.TRADE_CANCELLED).
         *
         * @return True if server responded as successful, false otherwise.
         * @throws JSONException when there is an error in parsing the response.
         */
        public boolean cancelTrade() throws JSONException {
            final Map<String, String> data = new HashMap();
            try {
                data.put("sessionid", URLDecoder.decode(SESSION_ID, "UTF-8"));
            } catch (final UnsupportedEncodingException e) {
                e.printStackTrace();
            }

            final String response = fetch(TRADE_URL + "cancel", "POST", data);

            if (response == null) {
                return false;
            }
            return (boolean) (new JSONObject(response)).get("success");
        }

        /**
         * Adds a message to trade chat.
         *
         * @param message The message to add to trade chat.
         * @return String representing server response
         */
        public String sendMessage(String message) {
            final Map<String, String> data = new HashMap<>();
            try {
                data.put("sessionid", URLDecoder.decode(SESSION_ID, "UTF-8"));
            } catch (final UnsupportedEncodingException e) {
                e.printStackTrace();
            }
            data.put("message", message);

            return fetch(TRADE_URL + "chat", "POST", data);
        }

        /**
         * Requests a String representation of an online file.
         *
         * @param url Location to fetch.
         * @param method "GET" or "POST"
         * @param data The data to be added to the data stream or request
         * params.
         * @return The server's String response to the request.
         */
        String fetch(String url, String method, Map<String, String> data) {
            return fetch(url, method, data, true);
        }

        /**
         * Requests a String representation of an online file (for Steam).
         *
         * @param url Location to fetch.
         * @param method "GET" or "POST"
         * @param data The data to be added to the data stream or request
         * params.
         * @param sendLoginData Whether or not to send login session data.
         * @return The server's String response to the request.
         */
        String fetch(String url, String method, Map<String, String> data, boolean sendLoginData) {
            String cookies = "";
            if (sendLoginData) {
                try {
                    cookies = "sessionid=" + URLEncoder.encode(SESSION_ID, "UTF-8") + "; steamLogin=" + STEAM_LOGIN + ";";
                } catch (UnsupportedEncodingException e) {
                }
            }
            final String response = request(url, method, data, cookies);
            return response;
        }

        /**
         * Requests a String representation of an online file (for Steam).
         *
         * @param url Location to fetch.
         * @param method "GET" or "POST"
         * @param data The data to be added to the data stream or request
         * params.
         * @param cookies A string of cookie data to be added to the request
         * headers.
         * @return The server's String response to the request.
         */
        String request(String url, String method, Map<String, String> data, String cookies) {
            boolean ajax = true;
            StringBuilder out = new StringBuilder();
            try {
                String dataString = "";
                if (data != null) {
                    for (final String key : data.keySet()) {
                        dataString += URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(data.get(key), "UTF-8") + "&";
                    }
                }
                if (!method.equals("POST")) {
                    url += "?" + dataString;
                }
                final URL url2 = new URL(url);
                final HttpURLConnection conn = (HttpURLConnection) url2.openConnection();
                conn.setRequestProperty("Cookie", cookies);
                conn.setRequestMethod(method);
                System.setProperty("http.agent", "");

                /**
                 * Previous User-Agent String for reference: "Mozilla/5.0
                 * (Windows; U; Windows NT 6.1; en-US; Valve Steam
                 * Client/1392853084; SteamTrade-Java Client; )
                 * AppleWebKit/535.19 (KHTML, like Gecko) Chrome/18.0.1025.166
                 * Safari/535.19"
                 */
                conn.setRequestProperty("User-Agent", "SteamTrade-Java/1.0 (Windows; U; Windows NT 6.1; en-US; Valve Steam Client/1392853084; SteamTrade-Java Client; ) AppleWebKit/535.19 (KHTML, like Gecko) Chrome/18.0.1025.166 Safari/535.19");

                conn.setRequestProperty("Host", "steamcommunity.com");
                conn.setRequestProperty("Content-type", "application/x-www-form-urlencoded; charset=UTF-8");
                conn.setRequestProperty("Accept", "text/javascript, text/hml, application/xml, text/xml, */*");

                // I don't know why, but we need a referer, otherwise we get a server error response.
                // Just use our trade URL as the referer since we have it on hand.
                conn.setRequestProperty("Referer", TRADE_URL);

                // Accept compressed responses.  (We can decompress it.)
                conn.setRequestProperty("Accept-Encoding", "gzip,deflate");

                if (ajax) {
                    conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
                    conn.setRequestProperty("X-Prototype-Version", "1.7");
                }

                if (method.equals("POST")) {
                    conn.setDoOutput(true);
                    final OutputStreamWriter os = new OutputStreamWriter(conn.getOutputStream());
                    os.write(dataString.substring(0, dataString.length() - 1));
                    os.flush();
                }

                java.io.InputStream netStream = conn.getInputStream();

                // If GZIPped response, then use the gzip decoder.
                if (conn.getContentEncoding().contains("gzip")) {
                    netStream = new java.util.zip.GZIPInputStream(netStream);
                }

                //cookies = conn.getHeaderField("Set-Cookie");
                final BufferedReader reader = new BufferedReader(new InputStreamReader(netStream));

                String line; // Stores an individual line currently being read.
                while ((line = reader.readLine()) != null) {
                    if (out.length() > 0) {
                        out.append('\n');
                    }
                    out.append(line);
                }
            } catch (final MalformedURLException e) {
                e.printStackTrace();
            } catch (final IOException e) {
                e.printStackTrace();
            }
            return out.toString();
        }
    }
}

/**
 * Private class that brutally scrapes the AppContextData JavaScript object from
 * the trade page. Without this, we would not know what inventories we have.
 *
 * @author nosoop
 */
class ContextScraper {

    static final List<AppContextPair> DEFAULT_APPCONTEXTDATA =
            new ArrayList<>();

    /**
     * Initialize default AppContextPairs.
     */
    static {
        DEFAULT_APPCONTEXTDATA.add(
                new AppContextPair(440, 2, "Team Fortress 2"));
    }

    /**
     * Scrapes the page for the g_rgAppContextData variable and passes it to a
     * private method for parsing, returning the list of named AppContextPair
     * objects it generates. It's a bit of a hack...
     *
     * @param pageResult The page data fetched by the TradeSession object.
     * @return A list of named AppContextPair objects representing the known
     * inventories, or an empty list if not found.
     */
    static List<AppContextPair> scrapeContextData(String pageResult)
            throws JSONException {
        try {
            BufferedReader read;
            read = new BufferedReader(new StringReader(pageResult));

            String buffer;
            while ((buffer = read.readLine()) != null) {
                String input;
                input = buffer.trim();

                if (input.startsWith("var g_rgAppContextData")) {
                    // Extract the JSON string from the JavaScript source.  Bleh
                    return parseContextData(input.substring
                            (input.indexOf('{'), input.length() - 1));
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        // If we can't find it, return an empty one, I guess...
        return DEFAULT_APPCONTEXTDATA;
    }

    /**
     * Parses the context data JSON feed and makes a bunch of AppContextPair
     * instances.
     *
     * @param json The JSON String representing g_rgAppContextData.
     * @return A list of named AppContextPair objects representing the available
     * inventories.
     */
    private static List<AppContextPair> parseContextData(String json)
            throws JSONException {
        List<AppContextPair> result = new ArrayList<>();

        JSONObject feedData = new JSONObject(json);

        for (String on : (Set<String>) feedData.keySet()) {
            JSONObject o = feedData.getJSONObject(on);
            if (o != null) {
                String gameName = o.getString("name");
                int appid = o.getInt("appid");

                JSONObject contextData = o.getJSONObject("rgContexts");

                for (String bn : (Set<String>) contextData.keySet()) {
                    JSONObject b = contextData.getJSONObject(bn);
                    String contextName = b.getString("name");
                    long contextid = Long.parseLong(b.getString("id"));
                    int assetCount = b.getInt("asset_count");

                    // "Team Fortress 2 - Backpack (226)"
                    String invNameFormat = String.format("%s - %s (%d)", 
                            gameName, contextName, assetCount);

                    // Only include the inventory if it's not empty.
                    if (assetCount > 0) {
                        result.add(new AppContextPair(
                                appid, contextid, invNameFormat));
                    }
                }
            }
        }
        return result;
    }
}