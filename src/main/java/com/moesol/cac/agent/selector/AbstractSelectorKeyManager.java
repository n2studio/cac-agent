package com.moesol.cac.agent.selector;

import java.net.Socket;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509KeyManager;

import com.moesol.cac.agent.CacHookingAgent;
import com.moesol.cac.agent.Config;

/**
 * When installed as the default key manager, this class prompts the user as
 * needed to choose a key. The default chooser uses Swing, but a Tty base
 * chooser can be set as a property.
 * 
 * @author hastings
 */
public abstract class AbstractSelectorKeyManager implements X509KeyManager, IdentityKeyListProvider {
	private String choosenAlias = null;
	private final Object keyStoreLock = new Object();
	private KeyStore keyStore;
	protected IdentityKeyChooser chooser = new SwingIdentityKeyChooser(this);

	public AbstractSelectorKeyManager() {
	}
	public void setIdentityKeyChooser(IdentityKeyChooser chooser) {
		this.chooser = chooser;
	}

	/**
	 * Injects this manager into the SSLContext.
	 * @param config 
	 * 
	 * @throws NoSuchAlgorithmException
	 * @throws KeyManagementException
	 */
	public static void configureSwingKeyManagerAsDefault(Config config) throws NoSuchAlgorithmException, KeyManagementException {
		if (CacHookingAgent.DEBUG) {
			System.out.println("Context: " + CacHookingAgent.CONTEXT);
		}
		KeyManager[] kmgrs = makeKeyManagers(config);
		SSLContext sslContext = SSLContext.getInstance(CacHookingAgent.CONTEXT);
		sslContext.init(kmgrs, null, new java.security.SecureRandom());
		SSLContext.setDefault(sslContext);

		HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
	}

	public static KeyManager[] makeKeyManagers(Config config) {
		String os = System.getProperty("os.name").toLowerCase();
		AbstractSelectorKeyManager keyManager;
		if (os.startsWith("win")) {
			System.out.println("Windows key manager");
			keyManager =  new WindowsSelectorKeyManager();
		} else {
			System.out.println("Linux key manager");
			keyManager = new Pkcs11SelectorKeyManager();
		}
		if (config.isTty()) {
			keyManager.setIdentityKeyChooser(new TtyIdentityKeyChooser(keyManager));
		}
		return new KeyManager[] { keyManager };
	}

	private KeyStore getKeyStore() {
		synchronized (keyStoreLock) {
			if (keyStore != null) {
				return keyStore;
			}
			try {
				keyStore = accessKeyStore();
			} catch (Exception e) {
				reportAndConvert(e);
			}
			return keyStore;
		}
	}

	protected abstract KeyStore accessKeyStore() throws Exception;

	@Override
	public synchronized String chooseClientAlias(final String[] keyType, final Principal[] issuers, Socket socket) {
		if (CacHookingAgent.DEBUG) {
			System.out.println("chooseClientAlias: ");
		}
		if (choosenAlias != null) {
			if (CacHookingAgent.DEBUG) {
				System.out.println("cached chooseClientAlias: " + choosenAlias);
			}
			return choosenAlias;
		}

		Config config = Config.loadFromUserHome();
		choosenAlias = config.getDefaultCertificateName();
		if (choosenAlias != null) {
			choosenAlias = choosenAlias.trim();
			if (!choosenAlias.isEmpty()) {
				if (CacHookingAgent.DEBUG) {
					System.out.println("config chooseClientAlias: " + choosenAlias);
				}
				return choosenAlias;
			}
		}

		try {
			final String[] aliases = getClientAliases(null, issuers);
			choosenAlias = chooser.chooseFromAliases(aliases);
		} catch (Exception e) {
			throw reportAndConvert(e);
		}
		if (CacHookingAgent.DEBUG) {
			System.out.println("chooseClientAlias: " + choosenAlias);
		}
		return choosenAlias;
	}

	@Override
	public CertDescription[] makeCertList(String[] aliases) {
		if (keyStore == null) {
			return new CertDescription[] { new CertDescription(null, "", "", "", ""), };
		}

		CertDescription[] result = new CertDescription[aliases.length];
		for (int i = 0; i < aliases.length; i++) {
			final String alias = aliases[i];
			Certificate cert;
			try {
				cert = keyStore.getCertificate(alias);
				X509Certificate x509 = (X509Certificate) cert;

				String purpose = X509PurposeDecoder.decode(x509);
				String principal = x509.getSubjectX500Principal().getName();
				Collection<List<?>> alt = x509.getSubjectAlternativeNames();
				String names = alt == null ? x509.getSubjectX500Principal().toString() : alt.toString();
				String issuer = x509.getIssuerX500Principal().getName();

				result[i] = new CertDescription(alias, principal, purpose, names, issuer);
			} catch (ClassCastException | KeyStoreException | CertificateParsingException e1) {
				result[i] = new CertDescription(alias, "", "", "", "");
			}
		}
		return result;
	}

	@Override
	public X509Certificate[] getCertificateChain(String alias) {
		try {
			return (X509Certificate[]) getKeyStore().getCertificateChain(alias);
		} catch (KeyStoreException e) {
			throw reportAndConvert(e);
		}
	}

	@Override
	public String[] getClientAliases(String keyType, Principal[] issuers) {
		if (CacHookingAgent.DEBUG) {
			System.out.println("getClientAliases: ");
		}
		try {
			KeyStore ks = getKeyStore();
			if (ks == null) {
				return new String[0];
			}
			ArrayList<String> asList = new ArrayList<String>();
			Enumeration<String> aliases = ks.aliases();
			while (aliases.hasMoreElements()) {
				// TODO filter by keyType/issuers?
				asList.add(aliases.nextElement());
			}
			return asList.toArray(new String[asList.size()]);
		} catch (KeyStoreException e) {
			throw reportAndConvert(e);
		}
	}

	@Override
	public PrivateKey getPrivateKey(String alias) {
		try {
			return (PrivateKey) getKeyStore().getKey(alias, null);
		} catch (Exception e) {
			throw reportAndConvert(e);
		}
	}

	@Override
	public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
		throw new UnsupportedOperationException("Client manager only");
	}

	@Override
	public String[] getServerAliases(String keyType, Principal[] issuers) {
		throw new UnsupportedOperationException("Client manager only");
	}

	public RuntimeException reportAndConvert(final Exception e) {
		chooser.reportException(e);
		return new RuntimeException(e);
	}

}