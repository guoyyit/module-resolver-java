module com.example.core {
    requires transitive com.example.api;
    exports com.example.core.spi;
    provides com.example.api.Greeter
        with com.example.core.HelloGreeter;
}
