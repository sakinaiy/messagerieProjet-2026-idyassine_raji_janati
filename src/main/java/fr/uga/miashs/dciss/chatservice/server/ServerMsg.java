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

package fr.uga.miashs.dciss.chatservice.server;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import fr.uga.miashs.dciss.chatservice.common.Packet;

import java.util.*;

public class ServerMsg {

    private final static Logger LOG = Logger.getLogger(ServerMsg.class.getName());
    public final static int SERVER_CLIENTID = 0;

    private transient ServerSocket serverSock;
    private transient boolean started;
    private transient ExecutorService executor;
    private transient ServerPacketProcessor sp;

    // maps pour associer les id aux users et groupes
    private Map<Integer, UserMsg> users;
    private Map<Integer, GroupMsg> groups;

    // séquences pour générer les identifiant d'utilisateurs et de groupe
    private AtomicInteger nextUserId;
    private AtomicInteger nextGroupId;

    public ServerMsg(int port) throws IOException {
        serverSock = new ServerSocket(port);
        started = false;
        users = new ConcurrentHashMap<>();
        groups = new ConcurrentHashMap<>();
        nextUserId = new AtomicInteger(1);
        nextGroupId = new AtomicInteger(-1);
        sp = new ServerPacketProcessor(this);
        executor = Executors.newCachedThreadPool();
    }

    public GroupMsg createGroup(int ownerId) {
        UserMsg owner = users.get(ownerId);
        if (owner == null) throw new ServerException("User with id=" + ownerId + " unknown. Group creation failed.");
        int id = nextGroupId.getAndDecrement();
        GroupMsg res = new GroupMsg(id, owner);
        groups.put(id, res);
        LOG.info("Group " + res.getId() + " created");
        return res;
    }

    public boolean removeGroup(int groupId) {
        GroupMsg g = groups.remove(groupId);
        if (g == null) return false;
        g.beforeDelete();
        return true;
    }

    public boolean removeUser(int userId) {
        UserMsg u = users.remove(userId);
        if (u == null) return false;
        u.beforeDelete();
        return true;
    }

    public UserMsg getUser(int userId) {
        return users.get(userId);
    }

    public GroupMsg getGroup(int groupId) {
        return groups.get(groupId);
    }

    // Methode utilisée pour savoir quoi faire d'un paquet reçu par le serveur
    public void processPacket(Packet p) {
        PacketProcessor pp = null;
        if (p.destId < 0) { // message de groupe
            // can be send only if sender is member
            UserMsg sender = users.get(p.srcId);
            GroupMsg g = groups.get(p.destId);
            if (g.getMembers().contains(sender)) pp = g;
        } else if (p.destId > 0) { // message entre utilisateurs
            pp = users.get(p.destId);
        } else { // message de gestion pour le serveur
            pp = sp;
        }

        if (pp != null) {
            pp.process(p);
        }
    }

    // envoie un paquet à tous les utilisateurs connectés
    public void broadcast(Packet p) {
        for (UserMsg user : users.values()) {
            if (user.isConnected()) {
                user.process(p);
            }
        }
    }

    /**
     * Sauvegarde les utilisateurs et groupes dans un fichier
     */
    public void saveData() {
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream("server_data.ser"))) {
            oos.writeObject(users);
            oos.writeObject(groups);
            oos.writeObject(nextUserId);
            oos.writeObject(nextGroupId);
            LOG.info("Données sauvegardées");
        } catch (IOException e) {
            LOG.warning("Erreur sauvegarde : " + e.getMessage());
        }
    }

    /**
     * Charge les utilisateurs et groupes depuis le fichier de sauvegarde
     */
    @SuppressWarnings("unchecked")
    public void loadData() {
        File f = new File("server_data.ser");
        if (!f.exists()) {
            LOG.info("Aucune donnée sauvegardée, démarrage à zéro");
            return;
        }
        try (ObjectInputStream ois = new ObjectInputStream(
                new FileInputStream(f))) {
            users = (Map<Integer, UserMsg>) ois.readObject();
            groups = (Map<Integer, GroupMsg>) ois.readObject();
            nextUserId = (AtomicInteger) ois.readObject();
            nextGroupId = (AtomicInteger) ois.readObject();
            LOG.info("Données chargées : " + users.size() + " utilisateurs, " + groups.size() + " groupes");
        } catch (IOException | ClassNotFoundException e) {
            LOG.warning("Erreur chargement : " + e.getMessage());
        }
    }

    public void start() {
        loadData(); // charger les données au démarrage
        started = true;
        while (started) {
            try {
                // le serveur attend une connexion d'un client
                Socket s = serverSock.accept();

                DataInputStream dis = new DataInputStream(s.getInputStream());
                DataOutputStream dos = new DataOutputStream(s.getOutputStream());

                // lit l'identifiant du client
                int userId = dis.readInt();
                // si 0 alors il faut créer un nouvel utilisateur et
                // envoyer l'identifiant au client
                if (userId == 0) {
                    userId = nextUserId.getAndIncrement();
                    dos.writeInt(userId);
                    dos.flush();
                    users.put(userId, new UserMsg(userId, this));
                }
                // si l'identifiant existe ou est nouveau alors
                // deux "taches"/boucles sont lancées en parallèle
                // une pour recevoir les messages du client,
                // une pour envoyer des messages au client
                // les deux boucles sont gérées au niveau de la classe UserMsg
                UserMsg x = users.get(userId);

                if (x != null && x.open(s)) {
                    LOG.info(userId + " connected");
                    // lancement boucle de reception
                    executor.submit(() -> x.receiveLoop());
                    // lancement boucle d'envoi
                    executor.submit(() -> x.sendLoop());
                } else { // si l'identifiant est inconnu, on ferme la connexion
                    s.close();
                }

            } catch (IOException e) {
                LOG.info("Close server");
                e.printStackTrace();
            }
        }
    }

    public void stop() {
        saveData(); // sauvegarder avant d'arrêter
        started = false;
        try {
            serverSock.close();
            users.values().forEach(s -> s.close());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws IOException {
        ServerMsg s = new ServerMsg(1666);
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> s.saveData()));
        
        // Thread pour arrêter proprement avec "quit"
        new Thread(() -> {
            Scanner sc = new Scanner(System.in);
            while (sc.hasNextLine()) {
                if (sc.nextLine().trim().equals("quit")) {
                    s.stop();
                    System.exit(0);
                }
            }
        }).start();
        
        s.start();
    }
}