package fr.uga.miashs.dciss.chatservice.client;

import java.io.File;
import java.sql.*;
import java.util.logging.Logger;

public class DatabaseManager {

    private final static Logger LOG = Logger.getLogger(DatabaseManager.class.getName());
    private Connection connection;
    private int userId;

    public DatabaseManager(int userId) {
        this.userId = userId;
        connect();
        createTables();
    }

    /**
     * Connexion à la base de données SQLite
     */
    private void connect() {
        try {
            String url = "jdbc:sqlite:client_" + userId + ".db";
            connection = DriverManager.getConnection(url);
            LOG.info("Connexion SQLite établie pour l'utilisateur " + userId);
        } catch (SQLException e) {
            LOG.warning("Erreur connexion SQLite : " + e.getMessage());
        }
    }

    /**
     * Création des tables si elles n'existent pas
     */
    private void createTables() {
        try (Statement stmt = connection.createStatement()) {

            // table des messages
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "src_id INTEGER NOT NULL," +
                "dest_id INTEGER NOT NULL," +
                "content TEXT NOT NULL," +
                "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );

            // table des contacts
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS contacts (" +
                "user_id INTEGER PRIMARY KEY," +
                "nickname TEXT" +
                ")"
            );

            // table des groupes
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS groups (" +
                "group_id INTEGER PRIMARY KEY," +
                "name TEXT" +
                ")"
            );

            // table des fichiers reçus
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS files (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "src_id INTEGER NOT NULL," +
                "file_name TEXT NOT NULL," +
                "file_path TEXT NOT NULL," +
                "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP" +
                ")"
            );

            LOG.info("Tables créées avec succès");

        } catch (SQLException e) {
            LOG.warning("Erreur création tables : " + e.getMessage());
        }
    }

    /**
     * Sauvegarder un message reçu
     */
    public void saveMessage(int srcId, int destId, String content) {
        String sql = "INSERT INTO messages (src_id, dest_id, content) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, srcId);
            ps.setInt(2, destId);
            ps.setString(3, content);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("Erreur sauvegarde message : " + e.getMessage());
        }
    }

    /**
     * Sauvegarder un contact avec son nickname
     */
    public void saveContact(int userId, String nickname) {
        String sql = "INSERT OR REPLACE INTO contacts (user_id, nickname) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, userId);
            ps.setString(2, nickname);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("Erreur sauvegarde contact : " + e.getMessage());
        }
    }

    /**
     * Sauvegarder un groupe
     */
    public void saveGroup(int groupId, String name) {
        String sql = "INSERT OR REPLACE INTO groups (group_id, name) VALUES (?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, groupId);
            ps.setString(2, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("Erreur sauvegarde groupe : " + e.getMessage());
        }
    }

    /**
     * Sauvegarder un fichier reçu
     */
    public void saveFile(int srcId, String fileName, String filePath) {
        String sql = "INSERT INTO files (src_id, file_name, file_path) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, srcId);
            ps.setString(2, fileName);
            ps.setString(3, filePath);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("Erreur sauvegarde fichier : " + e.getMessage());
        }
    }

    /**
     * Récupérer l'historique des messages
     */
    public void printMessages() {
        String sql = "SELECT * FROM messages ORDER BY timestamp";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                System.out.println("[" + rs.getString("timestamp") + "] " +
                    rs.getInt("src_id") + " -> " +
                    rs.getInt("dest_id") + " : " +
                    rs.getString("content"));
            }
        } catch (SQLException e) {
            LOG.warning("Erreur lecture messages : " + e.getMessage());
        }
    }

    /**
     * Fermer la connexion
     */
    public void close() {
        try {
            if (connection != null) connection.close();
            LOG.info("Connexion SQLite fermée");
        } catch (SQLException e) {
            LOG.warning("Erreur fermeture SQLite : " + e.getMessage());
        }
    }
}