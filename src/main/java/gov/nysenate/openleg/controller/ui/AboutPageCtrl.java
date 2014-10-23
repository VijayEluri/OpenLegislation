package gov.nysenate.openleg.controller.ui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

@Controller
@RequestMapping("/about/**")
public class AboutPageCtrl
{
    @RequestMapping(value = "", method = RequestMethod.GET)
    public String index() {
        return "about";
    }
}