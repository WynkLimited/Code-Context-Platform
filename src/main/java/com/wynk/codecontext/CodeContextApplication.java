package com.wynk.codecontext;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CodeContextApplication {
    public static void main(String[] args) { SpringApplication.run(CodeContextApplication.class, args); }
}
