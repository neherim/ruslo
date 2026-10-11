package io.github.neherim.ruslo.examples.cookbook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Application with all recipes: each package next to this class is a separate recipe. */
@SpringBootApplication
public class CookbookApplication {

    public static void main(String[] args) {
        SpringApplication.run(CookbookApplication.class, args);
    }
}
