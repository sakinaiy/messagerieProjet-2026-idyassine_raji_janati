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
        } else {
            LOG.warning("Server message of type=" + type + " not handled by procesor");
        }
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
            LOG.warning("Group " + groupId + " not found");
            return;
        }
        if (g.getOwner() != ownerId) {
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
            LOG.warning("Group " + groupId + " not found");
            return;
        }
        g.removeMember(server.getUser(userId));
    }

    public void leaveGroup(int userId, ByteBuffer data) {
        int groupId = data.getInt();
        GroupMsg g = server.getGroup(groupId);
        if (g == null) {
            LOG.warning("Group " + groupId + " not found");
            return;
        }
        g.removeMember(server.getUser(userId));
    }
}