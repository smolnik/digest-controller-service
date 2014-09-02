package net.adamsmolnik.control.fallback;

@FunctionalInterface
public interface Sender<T, R> {

    R send(T request, String url);

}
