package com.mycompany.migrador.relacional.mongodb.ejemplo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class DataBaseMigrator {

    public static void main(String[] args) {
        try (
            Connection conn = DriverManager.getConnection(Config.RELATIONAL_URL, Config.USERNAME, Config.PASSWORD);
            MongoClient mongoClient = MongoClients.create(Config.MONGO_URI)
        ) {
            MongoDatabase mongoDb = mongoClient.getDatabase(Config.MONGO_DB_NAME);

            migratePublishersWithGenres(conn, mongoDb);
            migrateTableAsCollection(conn, mongoDb, "VENTA", "VENTAS");
            migrateTableAsCollection(conn, mongoDb, "VIDEOJUEGO", "VIDEOJUEGOS");

            System.out.println("Migración completada exitosamente.");
            System.out.println("Colecciones creadas: [PUBLISHERS, VENTAS, VIDEOJUEGOS]");
        } catch (SQLException | RuntimeException e) {
            System.err.println("Error durante la migración: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void migratePublishersWithGenres(Connection conn, MongoDatabase mongoDb) throws SQLException {
        System.out.println("Migrando PUBLISHER → colección 'PUBLISHERS' (con GENEROS embebidos)...");

        String sqlPublishers = "SELECT CodigoPublisher, Nombre, Region, PrecioSuscripcion, EnServicio FROM PUBLISHER ORDER BY CodigoPublisher";

        try (Statement stmtPublishers = conn.createStatement(); ResultSet rsPublishers = stmtPublishers.executeQuery(sqlPublishers)) {

            List<Document> publishersDocs = new ArrayList<>();

            while (rsPublishers.next()) {
                int codigoPublisher = rsPublishers.getInt("CodigoPublisher");
                String nombre = rsPublishers.getString("Nombre");
                String region = rsPublishers.getString("Region");
                BigDecimal precioSuscripcion = rsPublishers.getBigDecimal("PrecioSuscripcion");
                String enServicio = rsPublishers.getString("EnServicio");

                Document publisherDoc = new Document()
                        .append("CodigoPublisher", codigoPublisher)
                        .append("Nombre", nombre)
                        .append("Region", region)
                        .append("PrecioSuscripcion", precioSuscripcion != null ? precioSuscripcion.doubleValue() : null)
                        .append("EnServicio", enServicio)
                        .append("Generos", new ArrayList<Document>());

                String sqlGeneros = "SELECT IdGenero, Nombre, Calificacion FROM GENERO WHERE CodigoPublisher = ?";
                try (PreparedStatement pstmtGeneros = conn.prepareStatement(sqlGeneros)) {
                    pstmtGeneros.setInt(1, codigoPublisher);
                    try (ResultSet rsGeneros = pstmtGeneros.executeQuery()) {
                        while (rsGeneros.next()) {
                            Document genero = new Document()
                                    .append("IdGenero", rsGeneros.getInt("IdGenero"))
                                    .append("Nombre", rsGeneros.getString("Nombre"))
                                    .append("Calificacion", rsGeneros.getInt("Calificacion"));

                            @SuppressWarnings("unchecked")
                            List<Document> generosList = (List<Document>) publisherDoc.get("Generos");
                            generosList.add(genero);
                        }
                    }
                }

                publishersDocs.add(publisherDoc);
            }

            if (!publishersDocs.isEmpty()) {
                MongoCollection<Document> collection = mongoDb.getCollection("PUBLISHERS");
                collection.insertMany(publishersDocs, new InsertManyOptions().ordered(false));
                System.out.println("Insertados " + publishersDocs.size() + " documentos en 'PUBLISHERS'");
            } else {
                System.out.println("No se encontraron registros en PUBLISHER.");
            }
        }
    }

    private static void migrateTableAsCollection(
            Connection conn,
            MongoDatabase mongoDb,
            String tableName,
            String collectionName) throws SQLException {

        System.out.println("Migrando tabla '" + tableName + "' → colección '" + collectionName + "'...");

        String query = "SELECT * FROM " + tableName;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(query)) {

            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            List<Document> documents = new ArrayList<>();

            while (rs.next()) {
                Document doc = new Document();

                for (int i = 1; i <= columnCount; i++) {
                    String columnName = metaData.getColumnName(i);
                    Object value = rs.getObject(i);

                    if (value instanceof Timestamp) {
                        value = ((Timestamp) value).toInstant();
                    } else if (value instanceof java.sql.Date) {
                        value = ((java.sql.Date) value).toLocalDate();
                    } else if (value instanceof BigDecimal) {
                        value = ((BigDecimal) value).doubleValue();
                    } else if (value instanceof Float) {
                        value = ((Float) value).doubleValue();
                    }

                    doc.append(columnName, value);
                }

                // Procesar SOLO para la tabla relacional VIDEOJUEGO
                if ("VIDEOJUEGO".equalsIgnoreCase(tableName)) {
                    processVideojuegoReferences(doc, mongoDb);
                }

                documents.add(doc);
            }

            if (!documents.isEmpty()) {
                MongoCollection<Document> collection = mongoDb.getCollection(collectionName);
                collection.insertMany(documents, new InsertManyOptions().ordered(false));
                System.out.println("Insertados " + documents.size() + " documentos en '" + collectionName + "'");
            } else {
                System.out.println("Tabla '" + tableName + "' vacía.");
            }
        }
    }

    /**
     * - Nuevos campos: PUBLISHER_ID y VENTA_ID (mayúsculas + guión bajo)
     * - Elimina campos antiguos
     */
    private static void processVideojuegoReferences(Document doc, MongoDatabase mongoDb) {
        // 1. Procesar Publisher
        Integer codPub = extractInteger(doc, "CODIGOPUBLISHER");
        if (codPub == null) codPub = extractInteger(doc, "CodigoPublisher");

        if (codPub != null) {
            // Buscar en PUBLISHERS usando "CodigoPublisher" 
            ObjectId pubId = getObjectIdByFieldValue(mongoDb, "PUBLISHERS", "CodigoPublisher", codPub);
            if (pubId != null) {
                doc.append("PUBLISHER_ID", pubId);  
            } else {
                System.err.println("Publisher no encontrado: CODIGOPUBLISHER = " + codPub);
            }
            // Eliminar variantes
            doc.remove("CODIGOPUBLISHER");
            doc.remove("CodigoPublisher");
        }

        // 2. Procesar Venta
        Integer codVen = extractInteger(doc, "CODIGOVENTA");
        if (codVen == null) codVen = extractInteger(doc, "CodigoVenta");

        if (codVen != null) {
            // Buscar en VENTAS usando "CODIGOVENTA" (mayúsculas)
            ObjectId venId = getObjectIdByFieldValue(mongoDb, "VENTAS", "CODIGOVENTA", codVen);
            if (venId != null) {
                doc.append("VENTA_ID", venId);  
            } else {
                System.err.println("Venta no encontrada: CODIGOVENTA = " + codVen);
            }
            // Eliminar variantes
            doc.remove("CODIGOVENTA");
            doc.remove("CodigoVenta");
        }
    }

    private static Integer extractInteger(Document doc, String fieldName) {
        Object value = doc.get(fieldName);
        if (value == null) return null;
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Double) return ((Double) value).intValue();
        if (value instanceof BigDecimal) return ((BigDecimal) value).intValue();
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private static ObjectId getObjectIdByFieldValue(MongoDatabase mongoDb, String collectionName, String fieldName, Object fieldValue) {
        MongoCollection<Document> collection = mongoDb.getCollection(collectionName);
        Document doc = collection.find(Filters.eq(fieldName, fieldValue))
                                 .projection(new Document("_id", 1))
                                 .first();
        return (doc != null) ? doc.getObjectId("_id") : null;
    }
}