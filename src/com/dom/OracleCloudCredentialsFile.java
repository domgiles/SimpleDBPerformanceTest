package com.dom;

import oracle.security.pki.OracleWallet;
import oracle.security.pki.textui.OraclePKIGenFunc;

import javax.crypto.Cipher;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// Credit to Kris Rice with minor improvements by Dominic Giles

public class OracleCloudCredentialsFile {

    private static final Logger logger = Logger.getLogger(OracleCloudCredentialsFile.class.getName());

    public static void recursiveDelete(final Path path) {
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, @SuppressWarnings("unused") BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                    if (e == null) {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                    // directory iteration failed
                    throw e;
                }
            });
            logger.fine(String.format("Deleted tmp directory : %s", path.toString()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete " + path, e);
        }
    }


    private static boolean testJCE() {
        int maxKeySize = 0;
        try {
            maxKeySize = Cipher.getMaxAllowedKeyLength("AES");
        } catch (NoSuchAlgorithmException ignored) {
        }
        return maxKeySize > 128;
    }

    public static Path setupSecureOracleCloudProperties(String passwd, String credentialsLocation, Boolean deleteOnExit) throws RuntimeException {
        try {
            if (!testJCE()) {
                throw new RuntimeException("Extended JCE support is not installed.");
            }
            Path tmp = Files.createTempDirectory("oracle_cloud_config");
            Path origfile = Paths.get(credentialsLocation);
            if (deleteOnExit) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> recursiveDelete(tmp)));
                ;
            }

            Path pzip = tmp.resolve("temp.zip");
            Files.copy(origfile, pzip);

            ZipFile zf = new ZipFile(pzip.toFile());
            Enumeration<? extends ZipEntry> entities = zf.entries();
            while (entities.hasMoreElements()) {
                ZipEntry entry = entities.nextElement();
                String name = entry.getName();
                Path p = tmp.resolve(name);
                Files.copy(zf.getInputStream(entry), p);
            }

            String pathToWallet = tmp.toFile().getAbsolutePath();

            System.setProperty("oracle.net.tns_admin", pathToWallet);
            System.setProperty("oracle.net.ssl_server_dn_match", "true");
            System.setProperty("oracle.net.ssl_version", "1.2");

            // open the CA's wallet
            OracleWallet caWallet = new OracleWallet();
            caWallet.open(pathToWallet, null);

            char[] keyAndTrustStorePasswd = OraclePKIGenFunc.getCreatePassword(passwd, false);

            // certs
            OracleWallet jksK = caWallet.migratePKCS12toJKS(keyAndTrustStorePasswd, OracleWallet.MIGRATE_KEY_ENTIRES_ONLY);
            // migrate (trusted) cert entries from p12 to different jks store
            OracleWallet jksT = caWallet.migratePKCS12toJKS(keyAndTrustStorePasswd, OracleWallet.MIGRATE_TRUSTED_ENTRIES_ONLY);
            String trustPath = pathToWallet + "/sqlclTrustStore.jks";
            String keyPath = pathToWallet + "/sqlclKeyStore.jks";

            jksT.saveAs(trustPath);
            jksK.saveAs(keyPath);

            System.setProperty("javax.net.ssl.trustStore", trustPath);
            System.setProperty("javax.net.ssl.trustStorePassword", passwd.toString());
            System.setProperty("javax.net.ssl.keyStore", keyPath);
            System.setProperty("javax.net.ssl.keyStorePassword", passwd.toString());
            java.security.Security.addProvider(new oracle.security.pki.OraclePKIProvider());
            return tmp;

        } catch (IOException e) {
            logger.fine(String.format("Unable to open and process the credentials file %s.", credentialsLocation));
            throw new RuntimeException(e);
        }
    }
}
