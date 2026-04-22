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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

import fr.uga.miashs.dciss.chatservice.common.Packet;

/**
 * Manages the connection to a ServerMsg.
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

    // --- AJOUT POUR LA RECONNEXION ---
    private static final String ID_FILE = "client_id.txt";

    /**
     * Sauvegarde l'ID dans un fichier local pour permettre la reconnexion
     */
    private void saveIdToFile(int id) {
        try (PrintWriter out = new PrintWriter(new FileWriter(ID_FILE))) {
            out.print(id);
        } catch (IOException e) {
            System.err.println("Erreur sauvegarde ID : " + e.getMessage());
        }
    }

    /**
     * Lit l'ID depuis le fichier local s'il existe
     */
    public static int loadIdFromFile() {
        File f = new File(ID_FILE);
        if (f.exists()) {
            try (Scanner scanner = new Scanner(f)) {
                if (scanner.hasNextInt()) return scanner.nextInt();
            } catch (IOException e) {
                System.err.println("Erreur lecture fichier ID");
            }
        }
        return 0; // 0 signifie qu'aucun ID n'est connu
    }
    // ---------------------------------

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

    /**
     * Modifié pour gérer la sauvegarde automatique de l'ID lors de la première connexion
     */
    public void startSession() throws UnknownHostException {
        if (s == null || s.isClosed()) {
            try {
                s = new Socket(serverAddress, serverPort);
                dos = new DataOutputStream(s.getOutputStream());
                dis = new DataInputStream(s.getInputStream());
                
                // Envoi de l'ID (soit 0 pour nouveau, soit l'ID chargé du fichier)
                dos.writeInt(identifier);
                dos.flush();

                if (identifier == 0) {
                    // Si on n'avait pas d'ID, le serveur nous en donne un nouveau
                    identifier = dis.readInt();
                    // On le sauvegarde pour les prochaines sessions
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

    /**
     * Envoie une demande de changement de pseudo au serveur (Type 0x30)
     */
    public void sendNickname(String nickname) {
        try {
            byte[] nickBytes = nickname.getBytes("UTF-8");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream tmpDos = new DataOutputStream(baos);
            
            tmpDos.writeByte(0x30); // Code d'action pour le pseudo
            tmpDos.writeInt(nickBytes.length);
            tmpDos.write(nickBytes);
            
            // Envoi au serveur (destination ID 0)
            sendPacket(0, baos.toByteArray());
        } catch (IOException e) {
            System.err.println("Erreur lors de l'envoi du pseudo : " + e.getMessage());
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
            // connection closed
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
        // Tente de charger l'ID sauvegardé avant de créer l'instance
        int savedId = loadIdFromFile();
        
        ClientMsg c = new ClientMsg(savedId, "localhost", 1666);

        // --- MISE À JOUR DU LISTENER POUR LES ERREURS ET PSEUDOS ---
        c.addMessageListener(p -> {
            if (p.data.length > 0) {
                byte type = p.data[0];
                if (type == 0x10) { // NOTIFICATION D'ERREUR
                    String errorMsg = new String(p.data, 1, p.data.length - 1, StandardCharsets.UTF_8);
                    System.err.println("🚨 [SERVEUR] " + errorMsg);
                } else if (type == 0x30) { // CHANGEMENT DE PSEUDO
                    ByteBuffer bb = ByteBuffer.wrap(p.data, 1, p.data.length - 1);
                    int len = bb.getInt();
                    byte[] nick = new byte[len];
                    bb.get(nick);
                    System.out.println("👤 L'utilisateur " + p.srcId + " est maintenant connu sous le nom : " + new String(nick, StandardCharsets.UTF_8));
                } else { // MESSAGE CLASSIQUE
                    System.out.println(p.srcId + " -> " + p.destId + ": " + new String(p.data));
                }
            }
        });
        
        c.addConnectionListener(active -> { if (!active) System.exit(0); });

        c.startSession();
        System.out.println("Votre ID est : " + c.getIdentifier());

        Scanner sc = new Scanner(System.in);
        System.out.println("Commandes : 'nick [pseudo]' ou 'msg [id] [texte]'");
        
        while(sc.hasNextLine()) {
            String line = sc.nextLine();
            if (line.startsWith("nick ")) {
                c.sendNickname(line.substring(5).trim());
            } else if (line.startsWith("msg ")) {
                String[] parts = line.split(" ", 3);
                if (parts.length == 3) {
                    c.sendPacket(Integer.parseInt(parts[1]), parts[2].getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }
}