package com.web.controller;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

import com.web.security.WorkspaceService;

/**
 * RW-3 (2026-10-12) — the tile home for someone with more than one job (decision D4): one tile per workspace they
 * have (auth-service rows, V27). Opening it refreshes the session copy the top-bar switcher reads.
 */
@Controller
public class WorkspaceController {

    @Autowired
    private WorkspaceService workspaces;

    @GetMapping("/workspaces")
    public ModelAndView workspaces(HttpServletRequest request) {
        ModelAndView mv = new ModelAndView("workspaces");
        mv.addObject("list", workspaces.load(request.getSession(true)));
        return mv;
    }
}
