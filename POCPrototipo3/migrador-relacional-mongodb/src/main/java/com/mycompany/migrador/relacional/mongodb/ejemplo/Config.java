/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.mycompany.migrador.relacional.mongodb.ejemplo;

/**
 *
 * @author User
 */
public class Config {
    // Base de datos relacional
    public static final String DB_TYPE = "oracle"; 
    public static final String RELATIONAL_URL = "jdbc:oracle:thin:@192.168.1.37:9999:XE";
    public static final String USERNAME = "NAME";
    public static final String PASSWORD = "NAME";

    // MongoDB
    public static final String MONGO_URI = "mongodb://localhost:27017";
    public static final String MONGO_DB_NAME = "POCVideojuegos";
}
