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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;
import fr.uga.miashs.dciss.chatservice.common.Packet;

public class ServerPacketProcessor implements PacketProcessor {
    private final static Logger LOG = Logger.getLogger(ServerPacketProcessor.class.getName());
    private ServerMsg server;

    public ServerPacketProcessor(ServerMsg s) {
        this.server = s;
    }

    @Override
    public void process(Packet p) {
        // ByteBufferVersion. On aurait pu utiliser un ByteArrayInputStream + DataInputStream à la place
        ByteBuffer buf = ByteBuffer.wrap(p.data);
        byte type = buf.get();

        if (type == 1) { // cas creation de groupe
            createGroup(p.srcId, buf);
        } else if (type == 2) { // suppression de groupe
            deleteGroup(p.srcId, buf);
        } else if (type == 3) { // ajout d'un membre
            addMember(p.srcId, buf);
        } else if (type == 4) { // retrait d'un membre
            removeMember(p.srcId, buf);
        } else if (type == 5) { // quitter un groupe
            leaveGroup(p.srcId, buf);
        } else if (type == 0x30) { // changement de pseudo (ajouté)
            updateNickname(p.srcId, buf, p.data);
        } else {
            sendError(p.srcId, "Type de message inconnu : " + type);
            LOG.warning("Server message of type=" + type + " not handled by processor");
        }
    }

    /**
     * Envoie une notification d'erreur au client (Type 0x10)
     */
    private void sendError(int userId, String message) {
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        ByteBuffer response = ByteBuffer.allocate(1 + msgBytes.length);
        response.put((byte) 0x10); // Code pour les erreurs
        response.put(msgBytes);
        
        // On envoie le paquet (srcId 0 = Serveur)
        server.processPacket(new Packet(0, userId, response.array()));
    }

    public void createGroup(int ownerId, ByteBuffer data) {
        int nb = data.getInt();
        GroupMsg g = server.createGroup(ownerId);
        for (int i = 0; i < nb; i++) {
            g.addMember(server.getUser(data.getInt()));
        }
    }

    public void deleteGroup(int ownerId, ByteBuffer data) {
        int groupId = data.getInt();
        GroupMsg g = server.getGroup(groupId);
        if (g == null) {
            sendError(ownerId, "Le groupe " + groupId + " n'existe pas.");
            LOG.warning("Group " + groupId + " not found");
            return;
        }
        if (g.getOwner() != ownerId) {
            sendError(ownerId, "Action refusee : vous n'etes pas le proprietaire du groupe " + groupId);
            LOG.warning("User " + ownerId + " is not owner of group " + groupId);
            return;
        }
        server.removeGroup(groupId);
    }

    public void addMember(int ownerId, ByteBuffer data) {
        int groupId = data.getInt();
        int userId = data.getInt();
        GroupMsg g = server.getGroup(groupId);
        if (g == null) {
            sendError(ownerId, "Impossible d'ajouter : le groupe " + groupId + " n'existe pas.");
            LOG.warning("Group " + groupId + " not found");
            return;
        }
        g.addMember(server.getUser(userId));
    }

    public void removeMember(int ownerId, ByteBuffer data) {
        int groupId = data.getInt();
        int userId = data.getInt();
        GroupMsg g = server.getGroup(groupId);
        if (g == null) {
            sendError(ownerId, "Impossible de retirer : le groupe " + groupId + " n'existe pas.");
            LOG.warning("Group " + groupId + " not found");
            return;
        }
        g.removeMember(server.getUser(userId));
    }

    public void leaveGroup(int userId, ByteBuffer data) {
        int groupId = data.getInt();
        GroupMsg g = server.getGroup(groupId);
        if (g == null) {
            sendError(userId, "Impossible de quitter : le groupe " + groupId + " n'existe pas.");
            LOG.warning("Group " + groupId + " not found");
            return;
        }
        g.removeMember(server.getUser(userId));
    }

    /**
     * Gère la mise à jour du pseudo et le diffuse aux autres clients
     */
    public void updateNickname(int userId, ByteBuffer buf, byte[] originalData) {
        int length = buf.getInt();
        byte[] nickBytes = new byte[length];
        buf.get(nickBytes);
        String nickname = new String(nickBytes, StandardCharsets.UTF_8);

        UserMsg user = server.getUser(userId);
        if (user != null) {
            user.setNickname(nickname);
            LOG.info("User " + userId + " changed nickname to: " + nickname);
            
            // On crée un paquet pour l'envoyer au broadcast
            server.broadcast(new Packet(userId, 0, originalData));
        }
    }
}