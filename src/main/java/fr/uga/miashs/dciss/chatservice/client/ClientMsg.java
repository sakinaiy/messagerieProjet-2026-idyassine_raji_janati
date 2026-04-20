/*
 * Copyright (c) 2024.  Jerome David. Univ. Grenoble Alpes.
 * This file is part of DcissChatService.
 *
 * DcissChatService is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * DcissChatService is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Foobar. If not, see <https://www.gnu.org/licenses/>.
 */

package fr.uga.miashs.dciss.chatservice.client;

import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.*;

import fr.uga.miashs.dciss.chatservice.common.Packet;

/**
 * Manages the connection to a ServerMsg. Method startSession() is used to
 * establish the connection. Then messages can be send by a call to sendPacket.
 * The reception is done asynchronously (internally by the method receiveLoop())
 * and the reception of a message is notified to MessagesListeners. To register
 * a MessageListener, the method addMessageListener has to be called. Session
 * are closed thanks to the method closeSession().
 */
public class ClientMsg {

    private String serverAddress;
    private int serverPort;

    private Socket s;
    private DataOutputStream dos;
    private DataInputStream dis;

    private int identifier;

    private List<MessageListener> mListeners;
    private List<ConnectionListener> cListeners;

    /**
     * Create a client with an existing id, that will connect to the server at the
     * given address and port
     *
     * @param id      The client id
     * @param address The server address or hostname
     * @param port    The port number
     */
    public ClientMsg(int id, String address, int port) {
        if (id < 0)
            throw new IllegalArgumentException("id must not be less than 0");
        if (port <= 0)
            throw new IllegalArgumentException("Server port must be greater than 0");
        serverAddress = address;
        serverPort = port;
        identifier = id;
        mListeners = new ArrayList<>();
        cListeners = new ArrayList<>();
    }

    /**
     * Create a client without id, the server will provide an id during the
     * session start
     *
     * @param address The server address or hostname
     * @param port    The port number
     */
    public ClientMsg(String address, int port) {
        this(0, address, port);
    }

    /**
     * Register a MessageListener to the client. It will be notified each time a
     * message is received.
     *
     * @param l
     */
    public void addMessageListener(MessageListener l) {
        if (l != null)
            mListeners.add(l);
    }

    protected void notifyMessageListeners(Packet p) {
        mListeners.forEach(x -> x.messageReceived(p));
    }

    /**
     * Register a ConnectionListener to the client. It will be notified if the connection start or ends.
     *
     * @param l
     */
    public void addConnectionListener(ConnectionListener l) {
        if (l != null)
            cListeners.add(l);
    }

    protected void notifyConnectionListeners(boolean active) {
        cListeners.forEach(x -> x.connectionEvent(active));
    }

    public int getIdentifier() {
        return identifier;
    }

    /**
     * Method to be called to establish the connection.
     *
     * @throws UnknownHostException
     * @throws IOException
     */
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
                }
                // start the receive loop
                new Thread(() -> receiveLoop()).start();
                notifyConnectionListeners(true);
            } catch (IOException e) {
                e.printStackTrace();
                closeSession();
            }
        }
    }

    /**
     * Send a packet to the specified destination (either a userId or groupId)
     *
     * @param destId the destination id
     * @param data   the data to be sent
     */
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

    /**
     * Envoie un message texte à un utilisateur ou un groupe
     *
     * @param destId  l'id du destinataire
     * @param message le texte du message
     */
    public void sendTextMessage(int destId, String message) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(bos);
            d.writeByte(0x20); // type message texte
            byte[] msgBytes = message.getBytes("UTF-8");
            d.writeInt(msgBytes.length);
            d.write(msgBytes);
            d.flush();
            sendPacket(destId, bos.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Envoie un fichier à un utilisateur ou un groupe
     *
     * @param destId   l'id du destinataire
     * @param fileName le nom du fichier
     * @param fileData les octets du fichier
     */
    public void sendFile(int destId, String fileName, byte[] fileData) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(bos);
            d.writeByte(0x21); // type envoi fichier
            byte[] nameBytes = fileName.getBytes("UTF-8");
            d.writeInt(nameBytes.length);
            d.write(nameBytes);
            d.writeInt(fileData.length);
            d.write(fileData);
            d.flush();
            sendPacket(destId, bos.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Crée un groupe avec les membres donnés
     *
     * @param memberIds les ids des membres
     */
    public void createGroup(int... memberIds) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(bos);
            d.writeByte(0x01); // type creation groupe
            d.writeInt(memberIds.length);
            for (int id : memberIds) {
                d.writeInt(id);
            }
            d.flush();
            sendPacket(0, bos.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Supprime un groupe
     *
     * @param groupId l'id du groupe
     */
    public void deleteGroup(int groupId) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(bos);
            d.writeByte(0x02); // type suppression groupe
            d.writeInt(groupId);
            d.flush();
            sendPacket(0, bos.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Ajoute un membre à un groupe
     *
     * @param groupId l'id du groupe
     * @param userId  l'id de l'utilisateur à ajouter
     */
    public void addMember(int groupId, int userId) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(bos);
            d.writeByte(0x03); // type ajout membre
            d.writeInt(groupId);
            d.writeInt(userId);
            d.flush();
            sendPacket(0, bos.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Retire un membre d'un groupe
     *
     * @param groupId l'id du groupe
     * @param userId  l'id de l'utilisateur à retirer
     */
    public void removeMember(int groupId, int userId) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(bos);
            d.writeByte(0x04); // type retrait membre
            d.writeInt(groupId);
            d.writeInt(userId);
            d.flush();
            sendPacket(0, bos.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Quitter un groupe
     *
     * @param groupId l'id du groupe
     */
    public void leaveGroup(int groupId) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(bos);
            d.writeByte(0x05); // type quitter groupe
            d.writeInt(groupId);
            d.flush();
            sendPacket(0, bos.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Start the receive loop. Has to be called only once.
     */
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
            // error, connection closed
        }
        closeSession();
    }

    public void closeSession() {
        try {
            if (s != null)
                s.close();
        } catch (IOException e) {
        }
        s = null;
        notifyConnectionListeners(false);
    }

    public static void main(String[] args) throws UnknownHostException, IOException, InterruptedException {
        ClientMsg c = new ClientMsg("localhost", 1666);

        // add a dummy listener that print the content of message as a string
        c.addMessageListener(p -> System.out.println(p.srcId + " says to " + p.destId + ": " + new String(p.data)));

        // add a connection listener that exit application when connection closed
        c.addConnectionListener(active -> { if (!active) System.exit(0); });

        c.startSession();
        System.out.println("Vous etes : " + c.getIdentifier());

        Scanner sc = new Scanner(System.in);
        String lu = null;
        while (!("\\quit").equals(lu)) {
            try {
                System.out.println("Que voulez vous faire ?");
                System.out.println("1 - Envoyer un message texte");
                System.out.println("2 - Creer un groupe");
                System.out.println("3 - Envoyer un fichier");
                System.out.println("4 - Quitter un groupe");
                int choix = Integer.parseInt(sc.nextLine());

                if (choix == 1) {
                    System.out.println("A qui voulez vous ecrire ?");
                    int dest = Integer.parseInt(sc.nextLine());
                    System.out.println("Votre message ?");
                    lu = sc.nextLine();
                    c.sendTextMessage(dest, lu);

                } else if (choix == 2) {
                    System.out.println("Combien de membres ?");
                    int nb = Integer.parseInt(sc.nextLine());
                    int[] members = new int[nb];
                    for (int i = 0; i < nb; i++) {
                        System.out.println("Id du membre " + (i + 1) + " ?");
                        members[i] = Integer.parseInt(sc.nextLine());
                    }
                    c.createGroup(members);

                } else if (choix == 3) {
                    System.out.println("A qui voulez vous envoyer le fichier ?");
                    int dest = Integer.parseInt(sc.nextLine());
                    System.out.println("Chemin du fichier ?");
                    String path = sc.nextLine();
                    File f = new File(path);
                    byte[] fileData = java.nio.file.Files.readAllBytes(f.toPath());

                    c.sendFile(dest, f.getName(), fileData);

                } else if (choix == 4) {
                    System.out.println("Id du groupe a quitter ?");
                    int groupId = Integer.parseInt(sc.nextLine());
                    c.leaveGroup(groupId);
                }

            } catch (InputMismatchException | NumberFormatException e) {
                System.out.println("Mauvais format");
            }
        }

        c.closeSession();
    }
}