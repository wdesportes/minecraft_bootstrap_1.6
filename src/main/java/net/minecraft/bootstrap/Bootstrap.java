package net.minecraft.bootstrap;

import java.awt.Font;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.math.BigInteger;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.FileChannel;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.swing.JFrame;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.Document;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

public class Bootstrap extends JFrame {
    private static final Font MONOSPACED = new Font("Monospaced", 0, 12);

    public static void closeSilently(final Closeable closeable) {
        if(closeable != null)
            try {
                closeable.close();
            }
            catch(final IOException ignored) {
            }
    }

    public static void copyFile(final File source, final File target) throws IOException {
        if(!target.exists())
            target.createNewFile();

        FileChannel sourceChannel = null;
        FileChannel targetChannel = null;
        try {
            sourceChannel = new FileInputStream(source).getChannel();
            targetChannel = new FileOutputStream(target).getChannel();
            targetChannel.transferFrom(sourceChannel, 0L, sourceChannel.size());
        }
        finally {
            if(sourceChannel != null)
                sourceChannel.close();

            if(targetChannel != null)
                targetChannel.close();
        }
    }

    public static void main(final String[] args) throws IOException {
        System.setProperty("java.net.preferIPv4Stack", "true");

        final OptionParser optionParser = new OptionParser();
        optionParser.allowsUnrecognizedOptions();

        optionParser.accepts("help", "Show help").forHelp();
        optionParser.accepts("force", "Force updating");

        final OptionSpec<String> proxyHostOption = optionParser.accepts("proxyHost", "Optional").withRequiredArg();
        final OptionSpec<Integer> proxyPortOption = optionParser.accepts("proxyPort", "Optional").withRequiredArg().defaultsTo("8080", new String[0]).ofType(Integer.class);
        final OptionSpec<String> proxyUserOption = optionParser.accepts("proxyUser", "Optional").withRequiredArg();
        final OptionSpec<String> proxyPassOption = optionParser.accepts("proxyPass", "Optional").withRequiredArg();
        final OptionSpec<File> workingDirectoryOption = optionParser.accepts("workDir", "Optional").withRequiredArg().ofType(File.class).defaultsTo(Util.getWorkingDirectory(), new File[0]);
        final OptionSpec<String> nonOptions = optionParser.nonOptions();
        OptionSet optionSet;
        try {
            optionSet = optionParser.parse(args);
        }
        catch(final OptionException e) {
            optionParser.printHelpOn(System.out);
            System.out.println("(to pass in arguments to minecraft directly use: '--' followed by your arguments");
            return;
        }

        if(optionSet.has("help")) {
            optionParser.printHelpOn(System.out);
            return;
        }

        final String hostName = optionSet.valueOf(proxyHostOption);
        Proxy proxy = Proxy.NO_PROXY;
        if(hostName != null)
            try {
                proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(hostName, optionSet.valueOf(proxyPortOption).intValue()));
            }
            catch(final Exception ignored) {
            }
        final String proxyUser = optionSet.valueOf(proxyUserOption);
        final String proxyPass = optionSet.valueOf(proxyPassOption);
        PasswordAuthentication passwordAuthentication = null;
        if(!proxy.equals(Proxy.NO_PROXY) && stringHasValue(proxyUser) && stringHasValue(proxyPass)) {
            passwordAuthentication = new PasswordAuthentication(proxyUser, proxyPass.toCharArray());

            final PasswordAuthentication auth = passwordAuthentication;
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return auth;
                }

            });
        }

        final File workingDirectory = optionSet.valueOf(workingDirectoryOption);
        if(workingDirectory.exists() && !workingDirectory.isDirectory())
            throw new FatalBootstrapError(new StringBuilder().append("Invalid working directory: ").append(workingDirectory).toString());
        if(!workingDirectory.exists() && !workingDirectory.mkdirs())
            throw new FatalBootstrapError(new StringBuilder().append("Unable to create directory: ").append(workingDirectory).toString());

        final List<String> strings = optionSet.valuesOf(nonOptions);
        final String[] remainderArgs = strings.toArray(new String[strings.size()]);

        final boolean force = optionSet.has("force");

        final Bootstrap frame = new Bootstrap(workingDirectory, proxy, passwordAuthentication, remainderArgs);
        try {
            frame.execute(force);
        }
        catch(final Throwable t) {
            final ByteArrayOutputStream stracktrace = new ByteArrayOutputStream();
            t.printStackTrace(new PrintStream(stracktrace));

            final StringBuilder report = new StringBuilder();
            report.append(stracktrace).append("\n\n-- Head --\nStacktrace:\n").append(stracktrace).append("\n\n").append(frame.outputBuffer);
            report.append("\tMinecraft.Bootstrap Version: 5");
            frame.println(new StringBuilder().append("FATAL ERROR: ").append(stracktrace.toString()).toString());
            frame.println("\nPlease fix the error and restart.");
        }
    }

    public static boolean stringHasValue(final String string) {
        return string != null && !string.isEmpty();
    }

    private final File workDir;
    private final Proxy proxy;
    private final File launcherJar;
    private final File launcherJarNew;
    private final JTextArea textArea;

    private final JScrollPane scrollPane;

    private final PasswordAuthentication proxyAuth;

    private final String[] remainderArgs;

    private final StringBuilder outputBuffer = new StringBuilder();

    public Bootstrap(final File workDir, final Proxy proxy, final PasswordAuthentication proxyAuth, final String[] remainderArgs) {
        super(BootstrapConstants.SERVER_NAME);
        this.workDir = workDir;
        this.proxy = proxy;
        this.proxyAuth = proxyAuth;
        this.remainderArgs = remainderArgs;
        launcherJar = new File(workDir, "launcher.jar");
        launcherJarNew = new File(workDir, "launcher.jar.new");

        setSize(854, 480);
        setDefaultCloseOperation(3);

        textArea = new JTextArea();
        textArea.setLineWrap(true);
        textArea.setEditable(false);
        textArea.setFont(MONOSPACED);
        ((DefaultCaret) textArea.getCaret()).setUpdatePolicy(1);

        scrollPane = new JScrollPane(textArea);
        scrollPane.setBorder(null);
        scrollPane.setVerticalScrollBarPolicy(22);

        add(scrollPane);
        setLocationRelativeTo(null);
        setVisible(true);

        println("Bootstrap (v5)");
        println(new StringBuilder().append("Current time is ").append(DateFormat.getDateTimeInstance(2, 2, Locale.US).format(new Date())).toString());
        println(new StringBuilder().append("System.getProperty('os.name') == '").append(System.getProperty("os.name")).append("'").toString());
        println(new StringBuilder().append("System.getProperty('os.version') == '").append(System.getProperty("os.version")).append("'").toString());
        println(new StringBuilder().append("System.getProperty('os.arch') == '").append(System.getProperty("os.arch")).append("'").toString());
        println(new StringBuilder().append("System.getProperty('java.version') == '").append(System.getProperty("java.version")).append("'").toString());
        println(new StringBuilder().append("System.getProperty('java.vendor') == '").append(System.getProperty("java.vendor")).append("'").toString());
        println(new StringBuilder().append("System.getProperty('sun.arch.data.model') == '").append(System.getProperty("sun.arch.data.model")).append("'").toString());
        println("");
    }

    public void execute(final boolean force) {
        if(launcherJarNew.isFile()) {
            println("Found cached update");
            renameNew();
        }

        final Downloader.Controller controller = new Downloader.Controller();
        final Downloader downloader = new Downloader(controller, this, proxy, null, launcherJarNew);

        if(force || !launcherJar.exists()) {
            downloader.run();

            if(controller.hasDownloadedLatch.getCount() != 0L)
                throw new FatalBootstrapError("Unable to download while being forced");

            renameNew();
        }
        else {
            final String md5 = getMd5(launcherJar);
            String md5File = BootstrapConstants.MD5_FILE;

            try {
                final HttpURLConnection connection = downloader.getConnection(new URL(md5File));
                final BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                md5File = reader.readLine();
                reader.close();
                System.out.println(md5);
                System.out.println(md5File);
            }
            catch(final Exception e) {
                println(new StringBuilder().append("Error: ").append(e.toString()).toString());
                downloader.suggestHelp(e);
            }

            if(!md5.equals(md5File)) {
                final Thread thread = new Thread(new Downloader(controller, this, proxy, md5, launcherJarNew));
                thread.setName("Launcher downloader");
                thread.start();
                try {
                    println("Looking for update");
                    final boolean wasInTime = controller.foundUpdateLatch.await(3L, TimeUnit.SECONDS);

                    if(controller.foundUpdate.get()) {
                        println("Found update in time, waiting to download");
                        controller.hasDownloadedLatch.await();
                        renameNew();
                    }
                    else if(!wasInTime)
                        println("Didn't find an update in time.");
                }
                catch(final InterruptedException e) {
                    throw new FatalBootstrapError(new StringBuilder().append("Got interrupted: ").append(e.toString()).toString());
                }
            }
            else
                println("Didn't find an update in time.");
        }

        startLauncher(launcherJar);
    }

    public String getMd5(final File file) {
        DigestInputStream stream = null;
        try {
            stream = new DigestInputStream(new FileInputStream(file), MessageDigest.getInstance("MD5"));
            final byte[] buffer = new byte[65536];

            int read = stream.read(buffer);
            while(read >= 1)
                read = stream.read(buffer);
        }
        catch(final Exception ignored) {
            final int read;
            return null;
        }
        finally {
            closeSilently(stream);
        }

        return String.format("%1$032x", new Object[] { new BigInteger(1, stream.getMessageDigest().digest()) });
    }

    private File getUnpackedLzmaFile(final File packedLauncherJar) {
        String filePath = packedLauncherJar.getAbsolutePath();
        if(filePath.endsWith(".lzma"))
            filePath = filePath.substring(0, filePath.length() - 5);
        return new File(filePath);
    }

    public void print(final String string) {
        System.out.print(string);

        outputBuffer.append(string);

        final Document document = textArea.getDocument();
        final JScrollBar scrollBar = scrollPane.getVerticalScrollBar();

        final boolean shouldScroll = scrollBar.getValue() + scrollBar.getSize().getHeight() + MONOSPACED.getSize() * 2 > scrollBar.getMaximum();
        try {
            document.insertString(document.getLength(), string, null);
        }
        catch(final BadLocationException ignored) {
        }
        if(shouldScroll)
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    scrollBar.setValue(2147483647);
                }
            });
    }

    public void println(final String string) {
        print(new StringBuilder().append(string).append("\n").toString());
    }

    public void renameNew() {
        if(launcherJar.exists() && !launcherJar.isFile() && !launcherJar.delete())
            throw new FatalBootstrapError(new StringBuilder().append("while renaming, target path: ").append(launcherJar.getAbsolutePath()).append(" is not a file and we failed to delete it").toString());

        if(launcherJarNew.isFile()) {
            println(new StringBuilder().append("Renaming ").append(launcherJarNew.getAbsolutePath()).append(" to ").append(launcherJar.getAbsolutePath()).toString());

            if(launcherJarNew.renameTo(launcherJar))
                println("Renamed successfully.");
            else {
                if(launcherJar.exists() && !launcherJar.canWrite())
                    throw new FatalBootstrapError(new StringBuilder().append("unable to rename: target").append(launcherJar.getAbsolutePath()).append(" not writable").toString());

                println("Unable to rename - could be on another filesystem, trying copy & delete.");

                if(launcherJarNew.exists() && launcherJarNew.isFile())
                    try {
                        copyFile(launcherJarNew, launcherJar);
                        if(launcherJarNew.delete())
                            println("Copy & delete succeeded.");
                        else
                            println(new StringBuilder().append("Unable to remove ").append(launcherJarNew.getAbsolutePath()).append(" after copy.").toString());
                    }
                    catch(final IOException e) {
                        throw new FatalBootstrapError(new StringBuilder().append("unable to copy:").append(e).toString());
                    }
                else
                    println("Nevermind... file vanished?");
            }
        }
    }

    public void startLauncher(final File launcherJar) {
        println("Starting launcher.");
        try {
            final Class aClass = new URLClassLoader(new URL[] { launcherJar.toURI().toURL() }).loadClass("net.minecraft.launcher.Launcher");
            final Constructor constructor = aClass.getConstructor(new Class[] { JFrame.class, File.class, Proxy.class, PasswordAuthentication.class, String[].class, Integer.class });
            constructor.newInstance(new Object[] { this, workDir, proxy, proxyAuth, remainderArgs, Integer.valueOf(5) });
        }
        catch(final Exception e) {
            throw new FatalBootstrapError(new StringBuilder().append("Unable to start: ").append(e).toString());
        }
    }
}