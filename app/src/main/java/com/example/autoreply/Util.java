package com.example.autoreply;

import java.util.Arrays;

public class Util {
    public static String argsToString(Object[] args) {
        if (args == null) return "[]";
        try {
            return Arrays.toString(args);
        } catch (Throwable t) {
            return "[?]";
        }
    }
}

