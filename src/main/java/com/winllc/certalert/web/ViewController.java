package com.winllc.certalert.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the search pages. All data reaches them through the DataTables endpoints. */
@Controller
public class ViewController {

    @GetMapping("/")
    public String index() {
        return "redirect:/users";
    }

    @GetMapping("/users")
    public String users() {
        return "users";
    }

    @GetMapping("/servers")
    public String servers() {
        return "servers";
    }

    /** The password fallback, for a browser that presented no client certificate. */
    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
