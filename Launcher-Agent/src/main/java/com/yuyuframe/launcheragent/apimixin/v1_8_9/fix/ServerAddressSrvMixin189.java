package com.yuyuframe.launcheragent.apimixin.v1_8_9.fix;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import javax.naming.directory.Attribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enregistrements SRV ({@code _minecraft._tcp.<domaine>}) jamais résolus en
 * 1.8.9 sous Java 25 — constaté le 2026-09-29 : uhcworld.fr et mcpvp.club
 * injoignables (connexion au domaine nu, qui pointe sur leur site Cloudflare),
 * Hypixel intact (pas de SRV).
 *
 * <p>Cause : {@code Main} de la 1.8.9 fait
 * {@code System.setProperty("java.net.preferIPv4Stack", "true")}, et la box
 * annonce un serveur DNS IPv6 en premier. Le client DNS de JNDI (NIO depuis
 * Java 13) lève alors {@code UnsupportedAddressTypeException} — une
 * RuntimeException, qui interrompt toute la requête au lieu de passer au
 * serveur IPv4 suivant comme le faisait Java 8. {@code resolveSrv} avale tout
 * ({@code catch Throwable}) et rend l'adresse telle quelle.
 *
 * <p>Même requête que vanilla, mais serveur par serveur : ceux en IPv6 sont
 * écartés quand la pile est en IPv4 seul, et l'échec de l'un n'empêche pas
 * d'essayer le suivant. Même valeur de retour que vanilla dans les deux cas.
 *
 * <p>Ne suffit pas seul : en jeu, patchy ({@code com.mojang.patchy.BlockingICFB},
 * liste de serveurs bloqués de Mojang) intercepte JNDI et échoue à instancier
 * {@code DnsContextFactory} depuis Java 17 (paquet non exporté). Le launcher
 * passe donc {@code --add-exports=jdk.naming.dns/com.sun.jndi.dns=ALL-UNNAMED}
 * ({@code jndi_dns_export_arg} dans {@code Backend/src/minecraft/launcher/jvm_args.rs}).
 * Vérifié hors jeu le 2026-09-29 avec patchy installé : il faut les deux.
 */
@Mixin(targets = "net.minecraft.network.ServerAddress")
public abstract class ServerAddressSrvMixin189 {

	private static final String DEFAULT_PORT = "25565";

	@Inject(method = "resolveSrv(Ljava/lang/String;)[Ljava/lang/String;", at = @At("HEAD"), cancellable = true, require = 0)
	private static void la$resolveSrvPerServer(String address, CallbackInfoReturnable<String[]> cir) {
		cir.setReturnValue(la$resolve(address));
	}

	private static String[] la$resolve(String address) {
		List<String> servers;
		try {
			servers = la$dnsServers();
		} catch (Throwable t) {
			LauncherLog.warn("[LauncherAgent] ServerAddressSrvMixin189: serveurs DNS introuvables : " + t);
			return new String[]{address, DEFAULT_PORT};
		}

		for (String server : servers) {
			try {
				DirContext ctx = new InitialDirContext(la$env(server));
				Attribute srv = ctx.getAttributes("_minecraft._tcp." + address, new String[]{"SRV"}).get("srv");
				if (srv == null) {
					// Réponse du serveur : pas de SRV pour ce domaine, inutile d'en interroger un autre.
					return new String[]{address, DEFAULT_PORT};
				}
				String[] parts = srv.get().toString().split(" ", 4);
				return new String[]{parts[3], parts[2]};
			} catch (javax.naming.NameNotFoundException e) {
				return new String[]{address, DEFAULT_PORT};
			} catch (Throwable t) {
				LauncherLog.agent(2, "[LauncherAgent] ServerAddressSrvMixin189: SRV de " + address
					+ " via " + server + " échoué : " + t);
			}
		}
		return new String[]{address, DEFAULT_PORT};
	}

	/**
	 * Liste de serveurs que JNDI utiliserait avec {@code dns:} (celle du
	 * système), un par URL, sans les IPv6 si la pile est en IPv4 seul.
	 */
	private static List<String> la$dnsServers() throws Exception {
		Object urls = new InitialDirContext(la$env("dns:")).getEnvironment().get("java.naming.provider.url");
		boolean ipv4Only = Boolean.getBoolean("java.net.preferIPv4Stack");
		List<String> servers = new ArrayList<>();
		if (urls != null) {
			for (String url : urls.toString().trim().split("\\s+")) {
				if (url.isEmpty()) continue;
				if (ipv4Only && url.startsWith("dns://[")) continue;
				servers.add(url);
			}
		}
		if (servers.isEmpty()) servers.add("dns:");
		return servers;
	}

	private static Hashtable<String, String> la$env(String providerUrl) {
		Hashtable<String, String> env = new Hashtable<>();
		env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
		env.put("java.naming.provider.url", providerUrl);
		env.put("com.sun.jndi.dns.timeout.retries", "1");
		return env;
	}
}
