package com.payneteasy.herdrwatch.usage.pull;

/** Хранилище есть, но не отдало содержимое (отказ в доступе, ошибка ОС). */
public class CredentialAccessException extends Exception {
    public CredentialAccessException(String message) {
        super(message);
    }
}
