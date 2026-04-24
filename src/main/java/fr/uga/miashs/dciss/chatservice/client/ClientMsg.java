package fr.uga.miashs.dciss.chatservice.client;

import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

import fr.uga.miashs.dciss.chatservice.common.Packet;

public class ClientMsg {

    private String serverAddress;
    private int serverPort;

    private Socket s;
    private DataOutputStream dos;
    private DataInputStream dis;

    private int identifier;

    private List<MessageListener> mListeners;
    private List<ConnectionListener> cListeners;

    private static final String ID_FILE = "client_id.txt";

    private void saveIdToFile(int id) {
        try (PrintWriter out = new PrintWriter(new FileWriter(ID_FILE))) {
            out.print(id);
        } catch (IOException e) {
            System.err.println("Erreur sauvegarde ID : " + e.getMessage());
        }
    }

    public static int loadIdFromFile() {
        File f = new File(ID_FILE);
        if (f.exists()) {
            try (Scanner scanner = new Scanner(f)) {
                if (scanner.hasNextInt()) return scanner.nextInt();
            } catch (IOException e) {
                System.err.println("Erreur lecture fichier ID");
            }
        }
        return 0;
    }

    public ClientMsg(int id, String address, int port) {
        if (id < 0) throw new IllegalArgumentException("id must not be less than 0");
        if (port <= 0) throw new IllegalArgumentException("Server port must be greater than 0");

        serverAddress = address;
        serverPort = port;
        identifier = id;
        mListeners = new ArrayList<>();
        cListeners = new ArrayList<>();
    }

    public ClientMsg(String address, int port) {
        this(0, address, port);
    }

    public void addMessageListener(MessageListener l) {
        if (l != null) mListeners.add(l);
    }

    protected void notifyMessageListeners(Packet p) {
        mListeners.forEach(x -> x.messageReceived(p));
    }

    public void addConnectionListener(ConnectionListener l) {
        if (l != null) cListeners.add(l);
    }

    protected void notifyConnectionListeners(boolean active) {
        cListeners.forEach(x -> x.connectionEvent(active));
    }

    public int getIdentifier() {
        return identifier;
    }

    public void startSession() throws UnknownHostException {
        if (s == null || s.isClosed()) {
            try {
                s = new Socket(serverAddress, serverPort);
                dos = new DataOutputStream(s.getOutputStream());
                dis = new DataInputStream(s.getInputStream());

                dos.writeInt(identifier);
                dos.flush();

                if (identifier == 0) {
                    identifier = dis.readInt();
                    saveIdToFile(identifier);
                }

                new Thread(() -> receiveLoop()).start();
                notifyConnectionListeners(true);

            } catch (IOException e) {
                e.printStackTrace();
                closeSession();
            }
        }
    }

    public void sendPacket(int destId, byte[] data) {
        try {
            synchronized (dos) {
                dos.writeInt(destId);
                dos.writeInt(data.length);
                dos.write(data);
                dos.flush();
            }
        } catch (IOException e) {
            closeSession();
        }
    }

    public void sendNickname(String nickname) {
        try {
            byte[] nickBytes = nickname.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream tmpDos = new DataOutputStream(baos);

            tmpDos.writeByte(0x30);
            tmpDos.writeInt(nickBytes.length);
            tmpDos.write(nickBytes);

            sendPacket(0, baos.toByteArray());

        } catch (IOException e) {
            System.err.println("Erreur lors de l'envoi du pseudo : " + e.getMessage());
        }
    }

    public void createGroup(int... memberIds) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream tmpDos = new DataOutputStream(baos);

            tmpDos.writeByte(1);
            tmpDos.writeInt(memberIds.length);

            for (int id : memberIds) {
                tmpDos.writeInt(id);
            }

            sendPacket(0, baos.toByteArray());

        } catch (IOException e) {
            System.err.println("Erreur création groupe : " + e.getMessage());
        }
    }

    public void addMember(int groupId, int userId) {
        sendGroupUserAction(3, groupId, userId);
    }

    public void removeMember(int groupId, int userId) {
        sendGroupUserAction(4, groupId, userId);
    }

    public void leaveGroup(int groupId) {
        sendGroupAction(5, groupId);
    }

    public void deleteGroup(int groupId) {
        sendGroupAction(2, groupId);
    }

    private void sendGroupAction(int type, int groupId) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream tmpDos = new DataOutputStream(baos);

            tmpDos.writeByte(type);
            tmpDos.writeInt(groupId);

            sendPacket(0, baos.toByteArray());

        } catch (IOException e) {
            System.err.println("Erreur action groupe : " + e.getMessage());
        }
    }

    private void sendGroupUserAction(int type, int groupId, int userId) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream tmpDos = new DataOutputStream(baos);

            tmpDos.writeByte(type);
            tmpDos.writeInt(groupId);
            tmpDos.writeInt(userId);

            sendPacket(0, baos.toByteArray());

        } catch (IOException e) {
            System.err.println("Erreur action membre : " + e.getMessage());
        }
    }

    private void receiveLoop() {
        try {
            while (s != null && !s.isClosed()) {
                int sender = dis.readInt();
                int dest = dis.readInt();
                int length = dis.readInt();

                byte[] data = new byte[length];
                dis.readFully(data);

                notifyMessageListeners(new Packet(sender, dest, data));
            }
        } catch (IOException e) {
            // connexion fermée
        }

        closeSession();
    }

    public void closeSession() {
        try {
            if (s != null) s.close();
        } catch (IOException e) {
        }

        s = null;
        notifyConnectionListeners(false);
    }

    public static void main(String[] args) throws UnknownHostException, IOException {
        int savedId = 0;

        ClientMsg c = new ClientMsg(savedId, "localhost", 1666);

        c.addMessageListener(p -> {
            if (p.data.length > 0) {
                byte type = p.data[0];

                if (type == 0x10) {
                    String errorMsg = new String(p.data, 1, p.data.length - 1, StandardCharsets.UTF_8);
                    System.err.println("[SERVEUR] " + errorMsg);

                } else if (type == 0x30) {
                    ByteBuffer bb = ByteBuffer.wrap(p.data, 1, p.data.length - 1);
                    int len = bb.getInt();

                    byte[] nick = new byte[len];
                    bb.get(nick);

                    System.out.println("L'utilisateur " + p.srcId + " est maintenant connu sous le nom : "
                            + new String(nick, StandardCharsets.UTF_8));

                } else {
                    System.out.println(p.srcId + " -> " + p.destId + ": "
                            + new String(p.data, StandardCharsets.UTF_8));
                }
            }
        });

        c.addConnectionListener(active -> {
            if (!active) System.exit(0);
        });

        c.startSession();

        System.out.println("Votre ID est : " + c.getIdentifier());
        System.out.println("Commandes :");
        System.out.println("nick [pseudo]");
        System.out.println("msg [id] [texte]");
        System.out.println("group [id1] [id2] ...");
        System.out.println("add [groupe] [id]");
        System.out.println("remove [groupe] [id]");
        System.out.println("leave [groupe]");
        System.out.println("delete [groupe]");

        Scanner sc = new Scanner(System.in);

        while (sc.hasNextLine()) {
            String line = sc.nextLine();

            if (line.startsWith("nick ")) {
                c.sendNickname(line.substring(5).trim());

            } else if (line.startsWith("msg ")) {
                String[] parts = line.split(" ", 3);

                if (parts.length == 3) {
                    c.sendPacket(Integer.parseInt(parts[1]), parts[2].getBytes(StandardCharsets.UTF_8));
                }

            } else if (line.startsWith("group ")) {
                String[] parts = line.split(" ");
                int[] ids = new int[parts.length - 1];

                for (int i = 1; i < parts.length; i++) {
                    ids[i - 1] = Integer.parseInt(parts[i]);
                }

                c.createGroup(ids);
                System.out.println("Demande de création de groupe envoyée.");

            } else if (line.startsWith("add ")) {
                String[] parts = line.split(" ");
                c.addMember(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));

            } else if (line.startsWith("remove ")) {
                String[] parts = line.split(" ");
                c.removeMember(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));

            } else if (line.startsWith("leave ")) {
                String[] parts = line.split(" ");
                c.leaveGroup(Integer.parseInt(parts[1]));

            } else if (line.startsWith("delete ")) {
                String[] parts = line.split(" ");
                c.deleteGroup(Integer.parseInt(parts[1]));

            } else {
                System.out.println("Commande inconnue.");
            }
        }

        sc.close();
    }
}