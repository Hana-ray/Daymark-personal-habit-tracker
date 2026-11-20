package portfolio.habit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    BCryptPasswordEncoder passwords() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(JdbcTemplate db) {
        return email -> db.query("SELECT email,password_hash FROM accounts WHERE email=?",
                (row, n) -> User.withUsername(row.getString(1)).password(row.getString(2)).roles("MEMBER").build(),
                email.strip().toLowerCase(java.util.Locale.ROOT))
                .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Account not found"));
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/register", "/style.css", "/health", "/error").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, denied) -> {
                String path = request.getServletPath();
                if ("POST".equals(request.getMethod())
                        && ("/login".equals(path) || "/register".equals(path))
                        && denied instanceof org.springframework.security.web.csrf.CsrfException) {
                    response.sendRedirect(request.getContextPath() + path + "?expired");
                    return;
                }
                response.sendError(403);
            }))
            .formLogin(login -> login.loginPage("/login").defaultSuccessUrl("/", true))
            .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
            .build();
    }
}
