package org.healthtg.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.healthtg.auth.AuthFailureException;
import org.healthtg.session.SessionService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
public class BearerSessionFilter extends OncePerRequestFilter {
    private final SessionService sessionService;

    public BearerSessionFilter(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null) {
            if (!authorization.startsWith("Bearer ") || authorization.length() == 7) {
                request.setAttribute(AuthFailureException.class.getName(),
                        new AuthFailureException("Session missing or expired"));
            } else {
                try {
                    UUID userId = sessionService.authenticate(authorization.substring(7));
                    CurrentUser principal = new CurrentUser(userId);
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
                } catch (AuthFailureException exception) {
                    request.setAttribute(AuthFailureException.class.getName(), exception);
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
