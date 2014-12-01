package gov.nysenate.openleg.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jsr310.JSR310Module;
import gov.nysenate.openleg.dao.bill.data.BillDao;
import gov.nysenate.openleg.util.OpenlegThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.converter.support.AllEncompassingFormHttpMessageConverter;
import org.springframework.http.converter.xml.Jaxb2RootElementHttpMessageConverter;
import org.springframework.http.converter.xml.SourceHttpMessageConverter;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import javax.annotation.PostConstruct;
import javax.xml.transform.Source;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
@EnableWebMvc
@EnableScheduling
@ComponentScan("gov.nysenate.openleg")
@Import({DatabaseConfig.class, SecurityConfig.class, ApplicationConfig.class})
public class WebApplicationConfig extends WebMvcConfigurerAdapter implements SchedulingConfigurer
{
    private static final Logger logger = LoggerFactory.getLogger(WebApplicationConfig.class);

    private static final String resourcePath = "/static/**";
    private static final String resourceLocation = "/static/";

    @PostConstruct
    public void init() {
        logger.info("Initialized WebApplication Config");
    }

    /** Sets paths that should not be intercepted by a controller (e.g css/ js/). */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        logger.info("Registering resource path {} for files under {}", resourcePath, resourceLocation);
        registry.addResourceHandler(resourcePath).addResourceLocations(resourceLocation).setCachePeriod(64000);
    }

    /**
     * This view resolver will map view names returned from the controllers to jsp files stored in the
     * configured 'prefix' path.
     */
    @Bean(name = "viewResolver")
    public InternalResourceViewResolver viewResolver() {
        InternalResourceViewResolver viewResolver = new InternalResourceViewResolver();
        viewResolver.setPrefix("/WEB-INF/view/");
        viewResolver.setSuffix(".jsp");
        return viewResolver;
    }

    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        StringHttpMessageConverter stringConverter = new StringHttpMessageConverter();
        stringConverter.setWriteAcceptCharset(false);
        converters.add(new ByteArrayHttpMessageConverter());
        converters.add(stringConverter);
        converters.add(new ResourceHttpMessageConverter());
        converters.add(new SourceHttpMessageConverter<Source>());
        converters.add(new AllEncompassingFormHttpMessageConverter());
        converters.add(new Jaxb2RootElementHttpMessageConverter());
        converters.add(jackson2Converter());
    }

    @Bean
    public MappingJackson2HttpMessageConverter jackson2Converter() {
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper());
        return converter;
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.registerModule(new GuavaModule());
        objectMapper.registerModule(new JSR310Module());
        return objectMapper;
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService schedulerThreadPool() {
        return Executors.newScheduledThreadPool(3, new OpenlegThreadFactory("scheduler"));
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setScheduler(schedulerThreadPool());
    }
}