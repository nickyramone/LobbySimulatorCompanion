package net.lobby_simulator_companion.loop;

import net.lobby_simulator_companion.loop.config.AppProperties;
import net.lobby_simulator_companion.loop.config.Settings;
import net.lobby_simulator_companion.loop.io.peer.Security;
import net.lobby_simulator_companion.loop.ui.DebugPanel;
import net.lobby_simulator_companion.loop.ui.Overlay;
import net.lobby_simulator_companion.loop.util.FileUtil;
import org.pcap4j.core.*;
import org.pcap4j.core.PcapNetworkInterface.PromiscuousMode;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.UdpPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.*;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;

public class Boot {
    public static PcapNetworkInterface nif = null;
    public static HashMap<Inet4Address, Timestamp> active = new HashMap<>();

    private static Logger logger;
    private static InetAddress localAddr;
    private static PcapHandle handle = null;
    private static Overlay ui;
    private static DebugPanel debugPanel;
    private static boolean running = true;

    public static void main(String[] args) throws Exception {
        configureLogger();
        try {
            init();
        } catch (Exception e) {
            logger.error("Failed to initialize application: {}", e.getMessage(), e);
            errorDialog("Failed to initialize application: " + e.getMessage());
            exitApplication(1);
        }

        if (!Settings.ENABLE_DEBUG_PANEL) {
            sniffPackets();
        }
    }

    private static void configureLogger() throws URISyntaxException {
        URI execUri = FileUtil.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path appHome = new File(execUri).toPath().getParent();
        System.setProperty("app.home", appHome.toString());
        logger = LoggerFactory.getLogger(Boot.class);
    }


    private static void init() throws Exception {
        logger.info("Initializing...");
        Factory.init();
        System.setProperty("jna.nosys", "true");
        if (!Sanity.check()) {
            System.exit(1);
        }
        Settings.set("autoload", Settings.get("autoload", "0")); //"autoload" is an ini-only toggle for advanced users.
        setupTray();

        logger.info("Setting up network interface...");
        setUpNetworkInterface();
        Factory.getPlayerbaseRepository().setNetworkInterface(nif);

        logger.info("Starting UI...");
        ui = Factory.getOverlay();

        if (Settings.ENABLE_DEBUG_PANEL) {
            Factory.getDebugPanel();
        }
    }

    private static void setUpNetworkInterface() throws PcapNativeException, IllegalAccessException, InterruptedException,
            UnknownHostException, InstantiationException, SocketException, UnsupportedLookAndFeelException,
            ClassNotFoundException, NotOpenException {

        getLocalAddr();
        nif = Pcaps.getDevByAddress(localAddr);
        if (nif == null) {
            JOptionPane.showMessageDialog(null,
                    "The device you selected doesn't seem to exist. Double-check the IP you entered.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        Security.nif = nif; // TODO: ugh! fix this asap!
        final int snapLen = 65536;
        final PromiscuousMode mode = PromiscuousMode.NONPROMISCUOUS;
        final int timeout = 0;
        handle = nif.openLive(snapLen, mode, timeout);

        // Berkley Packet Filter (BPF): http://biot.com/capstats/bpf.html
        handle.setFilter("udp && less 150", BpfProgram.BpfCompileMode.OPTIMIZE);
    }


    private static void sniffPackets() throws NotOpenException {
        // TODO: use handle.loop() instead? (http://www.tcpdump.org/pcap.html)
        while (running) {
            final Packet packet = handle.getNextPacket();

            if (packet != null) {
                final IpV4Packet ippacket = packet.get(IpV4Packet.class);

                if (ippacket != null) {
                    final UdpPacket udppack = ippacket.get(UdpPacket.class);

                    if (udppack != null && udppack.getPayload() != null) {
                        final Inet4Address srcAddr = ippacket.getHeader().getSrcAddr();
                        final Inet4Address dstAddr = ippacket.getHeader().getDstAddr();
                        final int payloadLen = udppack.getPayload().getRawData().length;

                        //Packets are STUN related: 56 is request, 68 is response
                        if (active.containsKey(srcAddr) && !srcAddr.equals(localAddr)) {
                            // it's a response from a peer to the local address
                            if (active.get(srcAddr) != null && payloadLen == 68 && dstAddr.equals(localAddr)) {
                                int ping = (int) (handle.getTimestamp().getTime() - active.get(srcAddr).getTime());
                                ui.setPing(ippacket.getHeader().getSrcAddr(), ping);
                                active.put(srcAddr, null); //No longer expect ping
                            }
                        } else {
                            if (payloadLen == 56 && srcAddr.equals(localAddr)) {
                                // it's a request from the local address to a peer
                                // we will store the peer address
                                Inet4Address peerAddresss = ippacket.getHeader().getDstAddr();
                                active.put(peerAddresss, handle.getTimestamp());
                            }
                        }
                    }
                }
            }
        }
    }


    public static void setupTray() throws AWTException, IOException {
        final AppProperties appProperties = Factory.getAppProperties();
        final SystemTray tray = SystemTray.getSystemTray();
        final PopupMenu popup = new PopupMenu();
        final MenuItem info = new MenuItem();
        final MenuItem exit = new MenuItem();

        BufferedImage trayIconImage = ImageIO.read(FileUtil.localResource("loop_logo.png"));
        int trayIconWidth = new TrayIcon(trayIconImage).getSize().width;
        TrayIcon trayIcon = new TrayIcon(trayIconImage.getScaledInstance(trayIconWidth, -1, Image.SCALE_SMOOTH));
        trayIcon.setPopupMenu(popup);
        trayIcon.setToolTip(appProperties.get("app.name"));

        info.addActionListener(e -> {
            String message = ""
                    + appProperties.get("app.name.short") + " is a tool to provide Dead By Daylight players more information about the lobby hosts.\n\n"
                    + "Features:\n"
                    + "======\n"
                    + "- Ping display:\n"
                    + "      The ping against the lobby/match host will be displayed on the overlay.\n\n"
                    + "- Rate user hosting the lobby/match:\n"
                    + "      As soon as the host name is detected, hold Shift and click on the name.\n"
                    + "      With every click, you will cycle between thumbs down, thumbs up and unrated.\n\n"
                    + "- Attach a description to the lobby/match host:\n"
                    + "      As soon as the host name is detected, right-click on the overlay to add/edit a description.\n\n"
                    + "- Visit the host's Steam profile:\n"
                    + "      As soon as the host name is detected, hold Shift and click on the Steam icon.\n"
                    + "      It will attempt to open the default browser on the host's Steam profile page.\n\n"
                    + "- Re-position the overlay:\n"
                    + "    Double-click to lock/unlock the overlay for dragging.\n"
                    + "\n"
                    + "Credits:\n"
                    + "=====\n"
                    + "Author of this fork: NickyRamone\n"
                    + "Original version and core: MLGA project, by PsiLupan & ShadowMoose";
            JOptionPane.showMessageDialog(null, message, appProperties.get("app.name"), JOptionPane.INFORMATION_MESSAGE);
        });

        exit.addActionListener(e -> {
            exitApplication(0);
        });
        info.setLabel("Help");
        exit.setLabel("Exit");
        popup.add(info);
        popup.add(exit);
        tray.add(trayIcon);
    }

    public static void exitApplication(int status) {
        running = false;
        SystemTray systemTray = SystemTray.getSystemTray();

        for (TrayIcon trayIcon : systemTray.getTrayIcons()) {
            systemTray.remove(trayIcon);
        }
        if (ui != null) {
            ui.close();
        }

        logger.info("Terminated UI.");

        if (handle != null) {
            logger.info("Cleaning up system resources. Could take a while...");
            handle.close();
            logger.info("Freed network interface handle.");
        }

        System.exit(status);
    }


    public static void getLocalAddr() throws InterruptedException, PcapNativeException, UnknownHostException, SocketException,
            ClassNotFoundException, InstantiationException, IllegalAccessException, UnsupportedLookAndFeelException {

        if (Settings.getDouble("autoload", 0) == 1) {
            localAddr = InetAddress.getByName(Settings.get("addr", ""));
            return;
        }

        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        final JFrame frame = new JFrame("Network Device");
        frame.setFocusableWindowState(true);

        final JLabel ipLab = new JLabel("Select LAN IP obtained from Network Settings:", JLabel.LEFT);
        final JComboBox<String> lanIP = new JComboBox<>();
        final JLabel lanLabel = new JLabel("If your device IP isn't in the dropdown, provide it below.");
        final JTextField lanText = new JTextField(Settings.get("addr", ""));

        ArrayList<InetAddress> inets = new ArrayList<InetAddress>();

        for (PcapNetworkInterface i : Pcaps.findAllDevs()) {
            for (PcapAddress x : i.getAddresses()) {
                InetAddress xAddr = x.getAddress();
                if (xAddr != null && x.getNetmask() != null && !xAddr.toString().equals("/0.0.0.0")) {
                    NetworkInterface inf = NetworkInterface.getByInetAddress(x.getAddress());
                    if (inf != null && inf.isUp() && !inf.isVirtual()) {
                        inets.add(xAddr);
                        lanIP.addItem((lanIP.getItemCount() + 1) + " - " + inf.getDisplayName() + " ::: " + xAddr.getHostAddress());
                        logger.info("Found: {} - {} ::: {}", lanIP.getItemCount(), inf.getDisplayName(), xAddr.getHostAddress());
                        Settings.set("addr", xAddr.getHostAddress().replaceAll("/", ""));
                    }
                }
            }
        }

        if (lanIP.getItemCount() == 0) {
            JOptionPane.showMessageDialog(null, "Unable to locate devices.\nPlease try running the program in Admin Mode.\nIf this does not work, you may need to reboot your computer.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        lanIP.setFocusable(false);
        final JButton start = new JButton("Start");
        start.addActionListener(e -> {
            try {
                if (lanText.getText().length() >= 7 && !lanText.getText().equals("0.0.0.0")) { // 7 is because the minimum field is 0.0.0.0
                    localAddr = InetAddress.getByName(lanText.getText());
                    logger.debug("Using IP from textfield: {}", lanText.getText());
                } else {
                    localAddr = inets.get(lanIP.getSelectedIndex());
                    logger.debug("Using device from dropdown: {}", lanIP.getSelectedItem());
                }
                Settings.set("addr", localAddr.getHostAddress().replaceAll("/", ""));
                frame.setVisible(false);
                frame.dispose();
            } catch (UnknownHostException e1) {
                logger.error("Encountered an invalid address.", e1);
            }
        });

        frame.setLayout(new GridLayout(5, 1));
        frame.add(ipLab);
        frame.add(lanIP);
        frame.add(lanLabel);
        frame.add(lanText);
        frame.add(start);
        frame.setAlwaysOnTop(true);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        while (frame.isVisible())
            Thread.sleep(10);
    }


    private static void errorDialog(String msg) {
        JOptionPane.showMessageDialog(null, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

}