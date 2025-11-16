package potato.baked.externalServerPing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;
import java.util.logging.Level;

/**
 * ExternalServerPing - multi-server version
 *
 * Config schema:
 *
 * servers:
 * - id: server_one
 * ip: example.com
 * port: 25565
 * update-interval: 30
 * debug: false
 * ping-only-if-players: false
 *
 * - id: server_two
 * ip: example.org
 * port: 25566
 * update-interval: 30
 * debug: false
 * ping-only-if-players: true
 *
 * Placeholders:
 * %externalserver_<id>_online%
 * %externalserver_<id>_max%
 * %externalserver_<id>_motd%
 * %externalserver_<id>_ping%
 * %externalserver_<id>_status%
 *
 * Legacy (first server only):
 * %externalserver_online%
 * %externalserver_max%
 * %externalserver_motd%
 * %externalserver_ping%
 * %externalserver_status%
 */
public final class ExternalServerPing extends JavaPlugin implements TabExecutor {

	// Per-server configuration + runtime state
	public static class ServerData {
		String id;
		String ip;
		int port;
		int updateInterval;
		boolean debug;
		boolean pingOnlyIfPlayers; // NEW: only ping when lobby has players

		BukkitTask pingTask;

		int onlinePlayers = 0;
		int maxPlayers = 0;
		String motd = "Unavailable";
		int ping = -1;
		boolean serverOnline = false;
	}

	// id (lowercased) -> server
	private final Map<String, ServerData> servers = new LinkedHashMap<>();

	@Override
	public void onEnable() {
		getLogger().info("=== ExternalServerPing v" + getDescription().getVersion() + " starting up ===");

		saveDefaultConfig();
		loadConfigValues();

		if (getCommand("externalserver") != null) {
			getCommand("externalserver").setExecutor(this);
			getCommand("externalserver").setTabCompleter(this);
		} else {
			getLogger().warning("Command 'externalserver' is not defined in plugin.yml.");
		}

		if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
			new ExternalServerPingPlaceholder().register();
			getLogger().info("PlaceholderAPI detected. Registered expansion 'externalserver'.");
		} else {
			getLogger().warning("PlaceholderAPI not found — placeholders will be unavailable.");
		}

		getLogger().info("ExternalServerPing enabled with " + servers.size() + " configured server(s).");
	}

	@Override
	public void onDisable() {
		getLogger().info("ExternalServerPing disabling. Cancelling ping tasks...");
		for (ServerData server : servers.values()) {
			if (server.pingTask != null) {
				server.pingTask.cancel();
			}
		}
		servers.clear();
		getLogger().info("ExternalServerPing disabled.");
	}

	/**
	 * Load configuration, support both:
	 * - NEW: list under 'servers:'
	 * - LEGACY: top-level 'server-ip', 'server-port', etc.
	 */
	private void loadConfigValues() {
		// Cancel old tasks
		for (ServerData s : servers.values()) {
			if (s.pingTask != null) {
				s.pingTask.cancel();
			}
		}
		servers.clear();

		getLogger().info("Loading configuration from config.yml...");

		List<Map<?, ?>> list = getConfig().getMapList("servers");
		if (list != null && !list.isEmpty()) {
			getLogger().info("Detected 'servers' list in config.yml with " + list.size() + " entries.");
			for (int i = 0; i < list.size(); i++) {
				Map<?, ?> entry = list.get(i);
				String id = getString(entry, "id", "server_" + (i + 1));
				String ip = getString(entry, "ip", null);
				Integer port = getBoxedInt(entry, "port", null);
				int interval = getInt(entry, "update-interval", 30);
				boolean debug = getBoolean(entry, "debug", false);
				boolean pingOnlyIfPlayers = getBoolean(entry, "ping-only-if-players", false);

				if (ip == null || port == null) {
					getLogger().warning("Entry #" + (i + 1) + " under 'servers' is missing 'ip' or 'port'. " +
							"This entry will be skipped. Raw: " + entry);
					continue;
				}

				String key = id.toLowerCase(Locale.ROOT);
				if (servers.containsKey(key)) {
					getLogger().warning("Duplicate server id '" + id + "' (case-insensitive). " +
							"Only the last one will be used.");
				}

				ServerData s = new ServerData();
				s.id = id;
				s.ip = ip;
				s.port = port;
				s.updateInterval = interval;
				s.debug = debug;
				s.pingOnlyIfPlayers = pingOnlyIfPlayers;

				servers.put(key, s);

				getLogger().info(String.format(
						"Loaded server #%d: id='%s', ip='%s', port=%d, update-interval=%ds, debug=%s, ping-only-if-players=%s",
						i + 1, id, ip, port, interval, debug, s.pingOnlyIfPlayers));

				startPinging(s);
			}

			if (servers.isEmpty()) {
				getLogger().severe("No valid servers were loaded from the 'servers' list. " +
						"Placeholders will not have any data.");
			} else {
				getLogger().info("Successfully loaded " + servers.size() + " server(s) from list schema.");
			}

			return;
		}

		// Legacy fallback: single server config
		getLogger().warning("No 'servers' list found in config.yml. Falling back to legacy single-server schema.");
		String ip = getConfig().getString("server-ip", null);
		int port = getConfig().getInt("server-port", -1);
		int interval = getConfig().getInt("update-interval", 30);
		boolean debug = getConfig().getBoolean("debug", false);

		if (ip == null || ip.trim().isEmpty() || port <= 0) {
			getLogger().severe("Legacy config is invalid or missing (server-ip/server-port). " +
					"No servers will be pinged. Please update config.yml to the new 'servers' list format.");
			return;
		}

		ServerData s = new ServerData();
		s.id = "default";
		s.ip = ip;
		s.port = port;
		s.updateInterval = interval;
		s.debug = debug;
		s.pingOnlyIfPlayers = false; // legacy: always ping

		servers.put("default", s);

		getLogger().info(String.format(
				"Loaded legacy server: id='default', ip='%s', port=%d, update-interval=%ds, debug=%s, ping-only-if-players=%s",
				ip, port, interval, debug, s.pingOnlyIfPlayers));

		startPinging(s);
		getLogger()
				.warning("You are using the legacy config format. Consider migrating to the 'servers:' list schema.");
	}

	// Helpers for config map access
	private String getString(Map<?, ?> map, String key, String def) {
		Object o = map.get(key);
		return o != null ? o.toString() : def;
	}

	private int getInt(Map<?, ?> map, String key, int def) {
		Object o = map.get(key);
		return o instanceof Number ? ((Number) o).intValue() : def;
	}

	private Integer getBoxedInt(Map<?, ?> map, String key, Integer def) {
		Object o = map.get(key);
		return o instanceof Number ? ((Number) o).intValue() : def;
	}

	private boolean getBoolean(Map<?, ?> map, String key, boolean def) {
		Object o = map.get(key);
		return o instanceof Boolean ? (Boolean) o : def;
	}

	public ServerData getServerById(String id) {
		if (id == null)
			return null;
		return servers.get(id.toLowerCase(Locale.ROOT));
	}

	public ServerData getFirstServer() {
		return servers.values().stream().findFirst().orElse(null);
	}

	/**
	 * Start a ping task for a given server.
	 */
	private void startPinging(ServerData server) {
		if (server.pingTask != null) {
			server.pingTask.cancel();
		}

		getLogger().info("Starting ping task for server '" + server.id + "' (" +
				server.ip + ":" + server.port + ") every " + server.updateInterval +
				" seconds (ping-only-if-players=" + server.pingOnlyIfPlayers + ").");

		final int protocolVersion = 754; // MC protocol, good enough for status

		server.pingTask = new BukkitRunnable() {
			@Override
			public void run() {
				int lobbyPlayers = getServer().getOnlinePlayers().size();

				// Only ping if there are players and config says so
				if (server.pingOnlyIfPlayers && lobbyPlayers == 0) {
					if (server.debug) {
						getLogger().fine("[ExternalServerPing] (" + server.id + ") Skipping ping - " +
								"ping-only-if-players=true and no lobby players.");
					}
					return;
				}

				long start = System.currentTimeMillis();
				try (Socket socket = new Socket()) {
					if (server.debug) {
						getLogger().info("[ExternalServerPing] (" + server.id + ") Connecting to " +
								server.ip + ":" + server.port + " (lobbyPlayers=" + lobbyPlayers + ")");
					}

					socket.connect(new InetSocketAddress(server.ip, server.port), 7000);
					OutputStream out = socket.getOutputStream();
					InputStream in = socket.getInputStream();

					// Handshake packet
					ByteArrayOutputStream handshakeBytes = new ByteArrayOutputStream();
					DataOutputStream handshake = new DataOutputStream(handshakeBytes);

					handshake.writeByte(0x00); // packet id
					writeVarInt(handshake, protocolVersion); // protocol version
					writeVarInt(handshake, server.ip.length());
					handshake.writeBytes(server.ip);
					handshake.writeShort(server.port);
					writeVarInt(handshake, 1); // next state: status

					// send handshake
					writeVarInt(out, handshakeBytes.size());
					out.write(handshakeBytes.toByteArray());

					// status request
					out.write(0x01); // size of packet
					out.write(0x00); // packet id for request

					// read response
					readVarInt(in); // packet size (ignored)
					int packetId = readVarInt(in);
					if (packetId != 0x00) {
						throw new IOException("Invalid packet ID (expected 0x00, got " + packetId + ")");
					}

					int stringLength = readVarInt(in);
					byte[] data = new byte[stringLength];
					int bytesRead = 0;
					while (bytesRead < data.length) {
						int result = in.read(data, bytesRead, data.length - bytesRead);
						if (result == -1) {
							throw new IOException("Unexpected end of stream while reading status response.");
						}
						bytesRead += result;
					}
					String json = new String(data);

					JsonObject jsonObject = JsonParser.parseString(json).getAsJsonObject();
					server.onlinePlayers = jsonObject.getAsJsonObject("players").get("online").getAsInt();
					server.maxPlayers = jsonObject.getAsJsonObject("players").get("max").getAsInt();

					if (jsonObject.get("description").isJsonPrimitive()) {
						server.motd = jsonObject.get("description").getAsString();
					} else {
						server.motd = jsonObject.getAsJsonObject("description").toString();
					}

					server.ping = (int) (System.currentTimeMillis() - start);
					server.serverOnline = true;

					if (server.debug) {
						getLogger().info(
								"[ExternalServerPing] (" + server.id + ") Success: online=" + server.onlinePlayers +
										", max=" + server.maxPlayers + ", motd=" + server.motd + ", ping=" + server.ping
										+ "ms");
					}

				} catch (Exception e) {
					server.onlinePlayers = 0;
					server.motd = "Unavailable";
					server.ping = -1;
					server.serverOnline = false;

					if (server.debug) {
						getLogger().log(Level.WARNING, "[ExternalServerPing] (" + server.id + ") Ping failed", e);
					} else {
						getLogger().fine("[ExternalServerPing] (" + server.id + ") Ping failed: " + e.getMessage());
					}
				}
			}
		}.runTaskTimerAsynchronously(this, 0L, 20L * server.updateInterval);
	}

	// VarInt helpers for Minecraft protocol

	private static void writeVarInt(OutputStream out, int value) throws IOException {
		while ((value & 0xFFFFFF80) != 0L) {
			out.write((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.write(value & 0x7F);
	}

	private static int readVarInt(InputStream in) throws IOException {
		int numRead = 0;
		int result = 0;
		byte read;
		do {
			read = (byte) in.read();
			int value = (read & 0b01111111);
			result |= (value << (7 * numRead));
			numRead++;
			if (numRead > 5) {
				throw new IOException("VarInt too big");
			}
		} while ((read & 0b10000000) != 0);
		return result;
	}

	// Command handling

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
			reloadConfig();
			loadConfigValues();
			sender.sendMessage("§a[ExternalServerPing] Configuration reloaded and ping tasks restarted!");
			getLogger().info("Configuration reloaded via /externalserver reload.");
			return true;
		}

		if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
			sender.sendMessage("§e[ExternalServerPing] Configured servers:");
			if (servers.isEmpty()) {
				sender.sendMessage("§c  (none)");
			} else {
				for (ServerData s : servers.values()) {
					sender.sendMessage(String.format(
							"§7  - §f%s§7 (%s:%d) online=%s players=%d/%d ping=%sms ping-only-if-players=%s",
							s.id, s.ip, s.port,
							s.serverOnline ? "§aOnline" : "§cOffline",
							s.onlinePlayers, s.maxPlayers,
							s.ping >= 0 ? s.ping : -1,
							s.pingOnlyIfPlayers));
				}
			}
			return true;
		}

		sender.sendMessage("§eUsage: /externalserver reload");
		sender.sendMessage("§e       /externalserver list");
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (args.length == 1) {
			return Arrays.asList("reload", "list");
		}
		return Collections.emptyList();
	}

	/**
	 * PlaceholderAPI expansion.
	 */
	public class ExternalServerPingPlaceholder extends PlaceholderExpansion {

		@Override
		public boolean canRegister() {
			return true;
		}

		@Override
		public boolean persist() {
			return true; // keeps this expansion registered after /papi reload
		}

		@Override
		public String getIdentifier() {
			return "externalserver";
		}

		@Override
		public String getAuthor() {
			return "BakedPotato";
		}

		@Override
		public String getVersion() {
			return ExternalServerPing.this.getDescription().getVersion();
		}

		@Override
		public String onPlaceholderRequest(Player player, String identifier) {
			if (identifier == null || identifier.isEmpty()) {
				return null;
			}

			String raw = identifier.trim();

			// Quick test placeholder: %externalserver_test%
			if (raw.equalsIgnoreCase("test")) {
				return "OK";
			}

			// Pattern: <id>_<metric> e.g. "server_one_status"
			int idx = raw.lastIndexOf('_');
			if (idx > 0) {
				String idPart = raw.substring(0, idx);
				String metricPart = raw.substring(idx + 1);

				ServerData server = getServerById(idPart);
				if (server != null) {
					return resolveMetric(server, metricPart);
				} else {
					getLogger()
							.fine("[ExternalServerPing] Placeholder requested for unknown server id '" + idPart + "'.");
					return "";
				}
			}

			// Legacy / no id: use the first configured server
			ServerData first = getFirstServer();
			if (first == null) {
				return "";
			}
			return resolveMetric(first, raw);
		}

		private String resolveMetric(ServerData server, String metricRaw) {
			if (metricRaw == null)
				return null;
			String metric = metricRaw.toLowerCase(Locale.ROOT);

			switch (metric) {
				case "online":
					return String.valueOf(server.onlinePlayers);
				case "max":
					return String.valueOf(server.maxPlayers);
				case "motd":
					return server.motd;
				case "ping":
					return server.ping >= 0 ? String.valueOf(server.ping) : "Unavailable";
				case "status":
					return server.serverOnline ? "Online" : "Offline";
				default:
					return null;
			}
		}
	}
}
