package ch.ahdis.fhir.hapi.jpa.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ca.uhn.fhir.jpa.rp.r4b.ImplementationGuideResourceProvider;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4b.model.ImplementationGuide;
import org.hl7.fhir.r4b.model.OperationOutcome;
import org.hl7.fhir.r4b.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4b.model.OperationOutcome.IssueType;
import org.hl7.fhir.r5.model.Enumerations.FHIRVersion;
import org.quartz.DisallowConcurrentExecution;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ca.uhn.fhir.jpa.dao.data.INpmPackageVersionDao;
import ca.uhn.fhir.jpa.model.entity.NpmPackageVersionEntity;
import ca.uhn.fhir.jpa.packages.PackageDeleteOutcomeJson;
import ca.uhn.fhir.jpa.packages.PackageInstallOutcomeJson;
import ca.uhn.fhir.jpa.packages.PackageInstallationSpec;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.fhir.model.api.Include;
import ca.uhn.fhir.model.api.annotation.Description;
import ca.uhn.fhir.rest.annotation.IncludeParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.RawParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.annotation.Sort;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.SearchContainedModeEnum;
import ca.uhn.fhir.rest.api.SearchTotalModeEnum;
import ca.uhn.fhir.rest.api.SortSpec;
import ca.uhn.fhir.rest.api.SummaryEnum;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.CompositeAndListParam;
import ca.uhn.fhir.rest.param.DateRangeParam;
import ca.uhn.fhir.rest.param.HasAndListParam;
import ca.uhn.fhir.rest.param.QuantityAndListParam;
import ca.uhn.fhir.rest.param.QuantityParam;
import ca.uhn.fhir.rest.param.ReferenceAndListParam;
import ca.uhn.fhir.rest.param.StringAndListParam;
import ca.uhn.fhir.rest.param.TokenAndListParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.param.UriAndListParam;
import ca.uhn.fhir.rest.server.SimpleBundleProvider;
import ch.ahdis.matchbox.CliContext;
import ch.ahdis.matchbox.MatchboxEngineSupport;
import ch.ahdis.matchbox.engine.MatchboxEngine;
import ch.ahdis.matchbox.engine.cli.VersionUtil;
import ch.ahdis.matchbox.util.MatchboxPackageInstallerImpl;

/**
 * $load and $load-all Operation for ImplementationGuide Resource (R4)
 *
 */
@DisallowConcurrentExecution
public class ImplementationGuideProviderR4B extends ImplementationGuideResourceProvider
		implements ApplicationContextAware, MatchboxImplementationGuideProvider {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImplementationGuideProviderR4B.class);

	@Autowired
	protected MatchboxEngineSupport matchboxEngineSupport;

	@Autowired
	private CliContext cliContext;

	@Override
	public MethodOutcome delete(HttpServletRequest theRequest, IIdType theResource, String theConditional,
			RequestDetails theRequestDetails) {
		ImplementationGuide guide = new ImplementationGuide();
		int pos = theResource.getIdPart().lastIndexOf("-");
		String version = theResource.getIdPart().substring(pos + 1);
		int space = version.indexOf(' ');
		if (space > 0) {
			version = version.substring(0, space);
		}
		guide.setVersion(version);
		String name = theResource.getIdPart().substring(0, pos);
		guide.setName(name);
		MethodOutcome outcome = new MethodOutcome();
		OperationOutcome oo = uninstall(guide);
		outcome.setOperationOutcome(oo);
		return outcome;
	}

	@Override
	public MethodOutcome update(HttpServletRequest theRequest, ImplementationGuide theResource, IIdType theId,
			String theConditional, RequestDetails theRequestDetails) {
		OperationOutcome oo = load(theResource);
		MethodOutcome outcome = new MethodOutcome();
		outcome.setOperationOutcome(oo);
		return outcome;
	}

	@Override
	public MethodOutcome create(HttpServletRequest theRequest, ImplementationGuide theResource, String theConditional,
			RequestDetails theRequestDetails) {
		OperationOutcome oo = load(theResource);
		MethodOutcome outcome = new MethodOutcome();
		outcome.setOperationOutcome(oo);
		return outcome;
	}

	@Autowired
	MatchboxPackageInstallerImpl packageInstallerSvc;

	@Autowired
	AppProperties appProperties;

	@Autowired
	private INpmPackageVersionDao myPackageVersionDao;

	@Autowired
	private PlatformTransactionManager myTxManager;

	public OperationOutcome getOperationOutcome(PackageInstallOutcomeJson pkgOutcome) {
		if (pkgOutcome == null) {
			return null;
		}
		OperationOutcome outcome = new OperationOutcome();
		for (String message : pkgOutcome.getMessage()) {
			outcome.addIssue().setSeverity(IssueSeverity.INFORMATION).setCode(IssueType.PROCESSING)
					.setDiagnostics(message);
		}
		for (String resource : pkgOutcome.getResourcesInstalled().keySet()) {
			outcome.addIssue().setSeverity(IssueSeverity.INFORMATION).setCode(IssueType.PROCESSING)
					.setDiagnostics(resource + ": " + pkgOutcome.getResourcesInstalled().get(resource));
		}
		return outcome;
	}

	public OperationOutcome getOperationOutcome(PackageDeleteOutcomeJson pkgOutcome) {
		if (pkgOutcome == null) {
			return null;
		}
		OperationOutcome outcome = new OperationOutcome();
		for (String message : pkgOutcome.getMessage()) {
			outcome.addIssue().setSeverity(IssueSeverity.INFORMATION).setCode(IssueType.PROCESSING)
					.setDiagnostics(message);
		}
		return outcome;
	}

	public OperationOutcome uninstall(ImplementationGuide theResource) {
		return getOperationOutcome(packageInstallerSvc.uninstall(this.getPackageInstallationSpec()
				.setPackageUrl(theResource.getUrl())
				.setName(theResource.getName())
				.setVersion(theResource.getVersion())));
	}

	public PackageInstallationSpec getPackageInstallationSpec() {
		return new PackageInstallationSpec()
				.addInstallResourceTypes(MatchboxPackageInstallerImpl.DEFAULT_INSTALL_TYPES.toArray(new String[0]))
				.setInstallMode(PackageInstallationSpec.InstallModeEnum.STORE_ONLY)
				.addDependencyExclude("hl7.fhir.r4.core")
				.addDependencyExclude("hl7.fhir.r4b.core")
				.addDependencyExclude("hl7.fhir.r5.core")
				.addDependencyExclude("hl7.terminology")
				.addDependencyExclude("hl7.terminology.r4")
				.addDependencyExclude("hl7.fhir.r4.examples");
	}

	public PackageInstallOutcomeJson load(ImplementationGuide theResource, PackageInstallOutcomeJson install) {
		PackageInstallOutcomeJson installOutcome = packageInstallerSvc
				.install(this.getPackageInstallationSpec().setName(theResource.getName())
						.setPackageUrl(theResource.getUrl()).setVersion(theResource.getVersion()));
		if (install != null) {
			install.getMessage().addAll(installOutcome.getMessage());
			return install;
		}
		return installOutcome;
	}

	public OperationOutcome load(ImplementationGuide theResource) {
		PackageInstallOutcomeJson installOutcome = packageInstallerSvc.install(this.getPackageInstallationSpec()
				.setPackageUrl(theResource.getUrl())
				.addInstallResourceTypes(MatchboxPackageInstallerImpl.DEFAULT_INSTALL_TYPES.toArray(new String[0]))
				.setName(theResource.getName())
				.setVersion(theResource.getVersion())
				.setInstallMode(PackageInstallationSpec.InstallModeEnum.STORE_ONLY));

		if (cliContext!=null && cliContext.getOnlyOneEngine()) {
			MatchboxEngine engine = matchboxEngineSupport.getMatchboxEngine(FHIRVersion._4_3_0.getDisplay(), this.cliContext, false, false);
			try {
				engine.loadPackage(theResource.getPackageId(), theResource.getVersion());
			} catch (Exception e) {
				log.error("Error loading package " + theResource.getPackageId() + " " + theResource.getVersion(), e);
			}
		} else {
			matchboxEngineSupport.getMatchboxEngine(FHIRVersion._4_3_0.getDisplay(), this.cliContext, false, true);
		}
		return getOperationOutcome(installOutcome);
	}

	public PackageInstallOutcomeJson loadAll(boolean replace) {
		matchboxEngineSupport.setInitialized(false);
		log.info("Initializing packages " + VersionUtil.getMemory());
		PackageInstallOutcomeJson installOutcome = null;
		if (appProperties.getImplementationGuides() != null) {
			Map<String, AppProperties.ImplementationGuide> guides = appProperties.getImplementationGuides();
			for (AppProperties.ImplementationGuide guide : guides.values()) {
				log.debug("Loading package " + guide.getName() + "#" + guide.getVersion() + " from property hapi.fhir" +
								 ".implementationGuides");
				boolean exists = new TransactionTemplate(myTxManager).execute(tx -> {
					Optional<NpmPackageVersionEntity> existing = myPackageVersionDao
							.findByPackageIdAndVersion(guide.getName(), guide.getVersion());
					return existing.isPresent();
				});
				if (!exists || replace) {
					ImplementationGuide ig = new ImplementationGuide();
					ig.setName(guide.getName());
					ig.setPackageId(guide.getName());
					ig.setUrl(guide.getUrl());
					ig.setVersion(guide.getVersion());
					installOutcome = load(ig, installOutcome);
				}
			}
		}
		log.info("Initializing packages finished " + VersionUtil.getMemory());

		if (this.appProperties.getOnly_install_packages() != null && this.appProperties.getOnly_install_packages()) {
			// In the 'only_install_packages' mode, we can stop after having installed the IGs in the database
			return installOutcome;
		}

		log.info("Creating cached engines during startup  " + VersionUtil.getMemory());
		//The matchboxEngineSupport will set the 'initialized' flag after having reloaded
		MatchboxEngine engine = matchboxEngineSupport.getMatchboxEngineNotSynchronized(null, this.cliContext, false,
																												 true);
		if (cliContext!=null && cliContext.getOnlyOneEngine()) {
			List<NpmPackageVersionEntity> packages = myPackageVersionDao
					.findAll(org.springframework.data.domain.Sort.by(Direction.ASC, "myPackageId", "myVersionId"));
			for (NpmPackageVersionEntity npmPackage : packages) {
				try {
					engine.loadPackage(npmPackage.getPackageId(), npmPackage.getVersionId());
				} catch (Exception e) {
					log.error("Error loading package " + npmPackage.getPackageId() + " " + npmPackage.getVersionId(), e);
				}
			}
		}
		log.info("Finished engines during startup  " + VersionUtil.getMemory());
		return installOutcome;
	}

	@Operation(name = "$load-all", type = ImplementationGuide.class, idempotent = false)
	public OperationOutcome loadAll() {
		return this.getOperationOutcome(loadAll(true));
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
	}

	@Override
	@Search(allowUnknownParams=true)
	public ca.uhn.fhir.rest.api.server.IBundleProvider search(
			jakarta.servlet.http.HttpServletRequest theServletRequest,
			jakarta.servlet.http.HttpServletResponse theServletResponse,

			ca.uhn.fhir.rest.api.server.RequestDetails theRequestDetails,

			@Description(shortDefinition="Search the contents of the resource's data using a filter")
			@OptionalParam(name=ca.uhn.fhir.rest.api.Constants.PARAM_FILTER)
			StringAndListParam theFtFilter,

			@Description(shortDefinition="Search the contents of the resource's data using a fulltext search")
			@OptionalParam(name=ca.uhn.fhir.rest.api.Constants.PARAM_CONTENT)
			StringAndListParam theFtContent, 

			@Description(shortDefinition="Search the contents of the resource's narrative using a fulltext search")
			@OptionalParam(name=ca.uhn.fhir.rest.api.Constants.PARAM_TEXT)
			StringAndListParam theFtText, 

			@Description(shortDefinition="Search for resources which have the given tag")
			@OptionalParam(name=ca.uhn.fhir.rest.api.Constants.PARAM_TAG)
			TokenAndListParam theSearchForTag, 

			@Description(shortDefinition="Search for resources which have the given security labels")
			@OptionalParam(name=ca.uhn.fhir.rest.api.Constants.PARAM_SECURITY)
			TokenAndListParam theSearchForSecurity, 
  
			@Description(shortDefinition="Search for resources which have the given profile")
			@OptionalParam(name=ca.uhn.fhir.rest.api.Constants.PARAM_PROFILE)
			UriAndListParam theSearchForProfile,

			@Description(shortDefinition="Search the contents of the resource's data using a list")
			@OptionalParam(name=ca.uhn.fhir.rest.api.Constants.PARAM_LIST)
			StringAndListParam theList,

			@Description(shortDefinition="The language of the resource")
			@OptionalParam(name=ca.uhn.fhir.rest.api.Constants.PARAM_LANGUAGE)
			TokenAndListParam theResourceLanguage,

			@Description(shortDefinition="Search for resources which have the given source value (Resource.meta.source)")
			@OptionalParam(name=ca.uhn.fhir.rest.api.Constants.PARAM_SOURCE)
			UriAndListParam theSearchForSource,

			@Description(shortDefinition="Return resources linked to by the given target")
			@OptionalParam(name="_has")
			HasAndListParam theHas, 

   

			@Description(shortDefinition="The ID of the resource")
			@OptionalParam(name="_id")
			TokenAndListParam the_id,
   

			@Description(shortDefinition="Only return resources which were last updated as specified by the given range")
			@OptionalParam(name="_lastUpdated")
			DateRangeParam the_lastUpdated, 
   

			@Description(shortDefinition="The profile of the resource")
			@OptionalParam(name="_profile")
			UriAndListParam the_profile, 
   

			@Description(shortDefinition="The security of the resource")
			@OptionalParam(name="_security")
			TokenAndListParam the_security,
   

			@Description(shortDefinition="The tag of the resource")
			@OptionalParam(name="_tag")
			TokenAndListParam the_tag,
   

			@Description(shortDefinition="Search on the narrative of the resource")
			@OptionalParam(name="_text")
			StringAndListParam the_text, 
   

			@Description(shortDefinition="Multiple Resources: * [CapabilityStatement](capabilitystatement.html): A use context assigned to the capability statement* [CodeSystem](codesystem.html): A use context assigned to the code system* [CompartmentDefinition](compartmentdefinition.html): A use context assigned to the compartment definition* [ConceptMap](conceptmap.html): A use context assigned to the concept map* [GraphDefinition](graphdefinition.html): A use context assigned to the graph definition* [ImplementationGuide](implementationguide.html): A use context assigned to the implementation guide* [MessageDefinition](messagedefinition.html): A use context assigned to the message definition* [NamingSystem](namingsystem.html): A use context assigned to the naming system* [OperationDefinition](operationdefinition.html): A use context assigned to the operation definition* [SearchParameter](searchparameter.html): A use context assigned to the search parameter* [StructureDefinition](structuredefinition.html): A use context assigned to the structure definition* [StructureMap](structuremap.html): A use context assigned to the structure map* [TerminologyCapabilities](terminologycapabilities.html): A use context assigned to the terminology capabilities* [ValueSet](valueset.html): A use context assigned to the value set")
			@OptionalParam(name="context")
			TokenAndListParam theContext,
   

			@Description(shortDefinition="Multiple Resources: * [CapabilityStatement](capabilitystatement.html): A quantity- or range-valued use context assigned to the capability statement* [CodeSystem](codesystem.html): A quantity- or range-valued use context assigned to the code system* [CompartmentDefinition](compartmentdefinition.html): A quantity- or range-valued use context assigned to the compartment definition* [ConceptMap](conceptmap.html): A quantity- or range-valued use context assigned to the concept map* [GraphDefinition](graphdefinition.html): A quantity- or range-valued use context assigned to the graph definition* [ImplementationGuide](implementationguide.html): A quantity- or range-valued use context assigned to the implementation guide* [MessageDefinition](messagedefinition.html): A quantity- or range-valued use context assigned to the message definition* [NamingSystem](namingsystem.html): A quantity- or range-valued use context assigned to the naming system* [OperationDefinition](operationdefinition.html): A quantity- or range-valued use context assigned to the operation definition* [SearchParameter](searchparameter.html): A quantity- or range-valued use context assigned to the search parameter* [StructureDefinition](structuredefinition.html): A quantity- or range-valued use context assigned to the structure definition* [StructureMap](structuremap.html): A quantity- or range-valued use context assigned to the structure map* [TerminologyCapabilities](terminologycapabilities.html): A quantity- or range-valued use context assigned to the terminology capabilities* [ValueSet](valueset.html): A quantity- or range-valued use context assigned to the value set")
			@OptionalParam(name="context-quantity")
			QuantityAndListParam theContext_quantity, 
   

			@Description(shortDefinition="Multiple Resources: * [CapabilityStatement](capabilitystatement.html): A type of use context assigned to the capability statement* [CodeSystem](codesystem.html): A type of use context assigned to the code system* [CompartmentDefinition](compartmentdefinition.html): A type of use context assigned to the compartment definition* [ConceptMap](conceptmap.html): A type of use context assigned to the concept map* [GraphDefinition](graphdefinition.html): A type of use context assigned to the graph definition* [ImplementationGuide](implementationguide.html): A type of use context assigned to the implementation guide* [MessageDefinition](messagedefinition.html): A type of use context assigned to the message definition* [NamingSystem](namingsystem.html): A type of use context assigned to the naming system* [OperationDefinition](operationdefinition.html): A type of use context assigned to the operation definition* [SearchParameter](searchparameter.html): A type of use context assigned to the search parameter* [StructureDefinition](structuredefinition.html): A type of use context assigned to the structure definition* [StructureMap](structuremap.html): A type of use context assigned to the structure map* [TerminologyCapabilities](terminologycapabilities.html): A type of use context assigned to the terminology capabilities* [ValueSet](valueset.html): A type of use context assigned to the value set")
			@OptionalParam(name="context-type")
			TokenAndListParam theContext_type,
   

			@Description(shortDefinition="Multiple Resources: * [CapabilityStatement](capabilitystatement.html): A use context type and quantity- or range-based value assigned to the capability statement* [CodeSystem](codesystem.html): A use context type and quantity- or range-based value assigned to the code system* [CompartmentDefinition](compartmentdefinition.html): A use context type and quantity- or range-based value assigned to the compartment definition* [ConceptMap](conceptmap.html): A use context type and quantity- or range-based value assigned to the concept map* [GraphDefinition](graphdefinition.html): A use context type and quantity- or range-based value assigned to the graph definition* [ImplementationGuide](implementationguide.html): A use context type and quantity- or range-based value assigned to the implementation guide* [MessageDefinition](messagedefinition.html): A use context type and quantity- or range-based value assigned to the message definition* [NamingSystem](namingsystem.html): A use context type and quantity- or range-based value assigned to the naming system* [OperationDefinition](operationdefinition.html): A use context type and quantity- or range-based value assigned to the operation definition* [SearchParameter](searchparameter.html): A use context type and quantity- or range-based value assigned to the search parameter* [StructureDefinition](structuredefinition.html): A use context type and quantity- or range-based value assigned to the structure definition* [StructureMap](structuremap.html): A use context type and quantity- or range-based value assigned to the structure map* [TerminologyCapabilities](terminologycapabilities.html): A use context type and quantity- or range-based value assigned to the terminology capabilities* [ValueSet](valueset.html): A use context type and quantity- or range-based value assigned to the value set")
			@OptionalParam(name="context-type-quantity", compositeTypes= { TokenParam.class, QuantityParam.class })
			CompositeAndListParam<TokenParam, QuantityParam> theContext_type_quantity,
   

			@Description(shortDefinition="Multiple Resources: * [CapabilityStatement](capabilitystatement.html): A use context type and value assigned to the capability statement* [CodeSystem](codesystem.html): A use context type and value assigned to the code system* [CompartmentDefinition](compartmentdefinition.html): A use context type and value assigned to the compartment definition* [ConceptMap](conceptmap.html): A use context type and value assigned to the concept map* [GraphDefinition](graphdefinition.html): A use context type and value assigned to the graph definition* [ImplementationGuide](implementationguide.html): A use context type and value assigned to the implementation guide* [MessageDefinition](messagedefinition.html): A use context type and value assigned to the message definition* [NamingSystem](namingsystem.html): A use context type and value assigned to the naming system* [OperationDefinition](operationdefinition.html): A use context type and value assigned to the operation definition* [SearchParameter](searchparameter.html): A use context type and value assigned to the search parameter* [StructureDefinition](structuredefinition.html): A use context type and value assigned to the structure definition* [StructureMap](structuremap.html): A use context type and value assigned to the structure map* [TerminologyCapabilities](terminologycapabilities.html): A use context type and value assigned to the terminology capabilities* [ValueSet](valueset.html): A use context type and value assigned to the value set")
			@OptionalParam(name="context-type-value", compositeTypes= { TokenParam.class, TokenParam.class })
			CompositeAndListParam<TokenParam, TokenParam> theContext_type_value,
   

			@Description(shortDefinition="Multiple Resources: * [CapabilityStatement](capabilitystatement.html): The capability statement publication date* [CodeSystem](codesystem.html): The code system publication date* [CompartmentDefinition](compartmentdefinition.html): The compartment definition publication date* [ConceptMap](conceptmap.html): The concept map publication date* [GraphDefinition](graphdefinition.html): The graph definition publication date* [ImplementationGuide](implementationguide.html): The implementation guide publication date* [MessageDefinition](messagedefinition.html): The message definition publication date* [NamingSystem](namingsystem.html): The naming system publication date* [OperationDefinition](operationdefinition.html): The operation definition publication date* [SearchParameter](searchparameter.html): The search parameter publication date* [StructureDefinition](structuredefinition.html): The structure definition publication date* [StructureMap](structuremap.html): The structure map publication date* [TerminologyCapabilities](terminologycapabilities.html): The terminology capabilities publication date* [ValueSet](valueset.html): The value set publication date")
			@OptionalParam(name="date")
			DateRangeParam theDate, 
   

			@Description(shortDefinition="Identity of the IG that this depends on")
			@OptionalParam(name="depends-on", targetTypes={  } )
			ReferenceAndListParam theDepends_on, 
   

			@Description(shortDefinition="Multiple Resources: * [CapabilityStatement](capabilitystatement.html): The description of the capability statement* [CodeSystem](codesystem.html): The description of the code system* [CompartmentDefinition](compartmentdefinition.html): The description of the compartment definition* [ConceptMap](conceptmap.html): The description of the concept map* [GraphDefinition](graphdefinition.html): The description of the graph definition* [ImplementationGuide](implementationguide.html): The description of the implementation guide* [MessageDefinition](messagedefinition.html): The description of the message definition* [NamingSystem](namingsystem.html): The description of the naming system* [OperationDefinition](operationdefinition.html): The description of the operation definition* [SearchParameter](searchparameter.html): The description of the search parameter* [StructureDefinition](structuredefinition.html): The description of the structure definition* [StructureMap](structuremap.html): The description of the structure map* [TerminologyCapabilities](terminologycapabilities.html): The description of the terminology capabilities* [ValueSet](valueset.html): The description of the value set")
			@OptionalParam(name="description")
			StringAndListParam theDescription, 
   

			@Description(shortDefinition="For testing purposes, not real usage")
			@OptionalParam(name="experimental")
			TokenAndListParam theExperimental,
   

			@Description(shortDefinition="Profile that all resources must conform to")
			@OptionalParam(name="global", targetTypes={  } )
			ReferenceAndListParam theGlobal, 
   

			@Description(shortDefinition="Multiple Resources: * [CapabilityStatement](capabilitystatement.html): Intended jurisdiction for the capability statement* [CodeSystem](codesystem.html): Intended jurisdiction for the code system* [ConceptMap](conceptmap.html): Intended jurisdiction for the concept map* [GraphDefinition](graphdefinition.html): Intended jurisdiction for the graph definition* [ImplementationGuide](implementationguide.html): Intended jurisdiction for the implementation guide* [MessageDefinition](messagedefinition.html): Intended jurisdiction for the message definition* [NamingSystem](namingsystem.html): Intended jurisdiction for the naming system* [OperationDefinition](operationdefinition.html): Intended jurisdiction for the operation definition* [SearchParameter](searchparameter.html): Intended jurisdiction for the search parameter* [StructureDefinition](structuredefinition.html): Intended jurisdiction for the structure definition* [StructureMap](structuremap.html): Intended jurisdiction for the structure map* [TerminologyCapabilities](terminologycapabilities.html): Intended jurisdiction for the terminology capabilities* [ValueSet](valueset.html): Intended jurisdiction for the value set")
			@OptionalParam(name="jurisdiction")
			TokenAndListParam theJurisdiction,
   

			@Description(shortDefinition="Multiple Resources: * [CapabilityStatement](capabilitystatement.html): Computationally friendly name of the capability statement* [CodeSystem](codesystem.html): Computationally friendly name of the code system* [CompartmentDefinition](compartmentdefinition.html): Computationally friendly name of the compartment definition* [ConceptMap](conceptmap.html): Computationally friendly name of the concept map* [GraphDefinition](graphdefinition.html): Computationally friendly name of the graph definition* [ImplementationGuide](implementationguide.html): Computationally friendly name of the implementation guide* [MessageDefinition](messagedefinition.html): Computationally friendly name of the message definition* [NamingSystem](namingsystem.html): Computationally friendly name of the naming system* [OperationDefinition](operationdefinition.html): Computationally friendly name of the operation definition* [SearchParameter](searchparameter.html): Computationally friendly name of the search parameter* [StructureDefinition](structuredefinition.html): Computationally friendly name of the structure definition* [StructureMap](structuremap.html): Computationally friendly name of the structure map* [TerminologyCapabilities](terminologycapabilities.html): Computationally friendly name of the terminology capabilities* [ValueSet](valueset.html): Computationally friendly name of the value set")
			@OptionalParam(name="name")
			StringAndListParam theName, 
   

			@Description(shortDefinition="Multiple Resources: * [CapabilityStatement](capabilitystatement.html): Name of the publisher of the capability statement* [CodeSystem](codesystem.html): Name of the publisher of the code system* [CompartmentDefinition](compartmentdefinition.html): Name of the publisher of the compartment definition* [ConceptMap](conceptmap.html): Name of the publisher of the concept map* [GraphDefinition](graphdefinition.html): Name of the publisher of the graph definition* [ImplementationGuide](implementationguide.html): Name of the publisher of the implementation guide* [MessageDefinition](messagedefinition.html): Name of the publisher of the message definition* [NamingSystem](namingsystem.html): Name of the publisher of the naming system* [OperationDefinition](operationdefinition.html): Name of the publisher of the operation definition* [SearchParameter](searchparameter.html): Name of the publisher of the search parameter* [StructureDefinition](structuredefinition.html): Name of the publisher of the structure definition* [StructureMap](structuremap.html): Name of the publisher of the structure map* [TerminologyCapabilities](terminologycapabilities.html): Name of the publisher of the terminology capabilities* [ValueSet](valueset.html): Name of the publisher of the value set")
			@OptionalParam(name="publisher")
			StringAndListParam thePublisher, 
   

			@Description(shortDefinition="Location of the resource")
			@OptionalParam(name="resource", targetTypes={  } )
			ReferenceAndListParam theResource, 
   

			@Description(shortDefinition="Multiple Resources: * [CapabilityStatement](capabilitystatement.html): The current status of the capability statement* [CodeSystem](codesystem.html): The current status of the code system* [CompartmentDefinition](compartmentdefinition.html): The current status of the compartment definition* [ConceptMap](conceptmap.html): The current status of the concept map* [GraphDefinition](graphdefinition.html): The current status of the graph definition* [ImplementationGuide](implementationguide.html): The current status of the implementation guide* [MessageDefinition](messagedefinition.html): The current status of the message definition* [NamingSystem](namingsystem.html): The current status of the naming system* [OperationDefinition](operationdefinition.html): The current status of the operation definition* [SearchParameter](searchparameter.html): The current status of the search parameter* [StructureDefinition](structuredefinition.html): The current status of the structure definition* [StructureMap](structuremap.html): The current status of the structure map* [TerminologyCapabilities](terminologycapabilities.html): The current status of the terminology capabilities* [ValueSet](valueset.html): The current status of the value set")
			@OptionalParam(name="status")
			TokenAndListParam theStatus,
   

			@Description(shortDefinition="Multiple Resources: * [CapabilityStatement](capabilitystatement.html): The human-friendly name of the capability statement* [CodeSystem](codesystem.html): The human-friendly name of the code system* [ConceptMap](conceptmap.html): The human-friendly name of the concept map* [ImplementationGuide](implementationguide.html): The human-friendly name of the implementation guide* [MessageDefinition](messagedefinition.html): The human-friendly name of the message definition* [OperationDefinition](operationdefinition.html): The human-friendly name of the operation definition* [StructureDefinition](structuredefinition.html): The human-friendly name of the structure definition* [StructureMap](structuremap.html): The human-friendly name of the structure map* [TerminologyCapabilities](terminologycapabilities.html): The human-friendly name of the terminology capabilities* [ValueSet](valueset.html): The human-friendly name of the value set")
			@OptionalParam(name="title")
			StringAndListParam theTitle, 
   

			@Description(shortDefinition="Multiple Resources: * [CapabilityStatement](capabilitystatement.html): The uri that identifies the capability statement* [CodeSystem](codesystem.html): The uri that identifies the code system* [CompartmentDefinition](compartmentdefinition.html): The uri that identifies the compartment definition* [ConceptMap](conceptmap.html): The uri that identifies the concept map* [GraphDefinition](graphdefinition.html): The uri that identifies the graph definition* [ImplementationGuide](implementationguide.html): The uri that identifies the implementation guide* [MessageDefinition](messagedefinition.html): The uri that identifies the message definition* [OperationDefinition](operationdefinition.html): The uri that identifies the operation definition* [SearchParameter](searchparameter.html): The uri that identifies the search parameter* [StructureDefinition](structuredefinition.html): The uri that identifies the structure definition* [StructureMap](structuremap.html): The uri that identifies the structure map* [TerminologyCapabilities](terminologycapabilities.html): The uri that identifies the terminology capabilities* [ValueSet](valueset.html): The uri that identifies the value set")
			@OptionalParam(name="url")
			UriAndListParam theUrl, 
   

			@Description(shortDefinition="Multiple Resources: * [CapabilityStatement](capabilitystatement.html): The business version of the capability statement* [CodeSystem](codesystem.html): The business version of the code system* [CompartmentDefinition](compartmentdefinition.html): The business version of the compartment definition* [ConceptMap](conceptmap.html): The business version of the concept map* [GraphDefinition](graphdefinition.html): The business version of the graph definition* [ImplementationGuide](implementationguide.html): The business version of the implementation guide* [MessageDefinition](messagedefinition.html): The business version of the message definition* [OperationDefinition](operationdefinition.html): The business version of the operation definition* [SearchParameter](searchparameter.html): The business version of the search parameter* [StructureDefinition](structuredefinition.html): The business version of the structure definition* [StructureMap](structuremap.html): The business version of the structure map* [TerminologyCapabilities](terminologycapabilities.html): The business version of the terminology capabilities* [ValueSet](valueset.html): The business version of the value set")
			@OptionalParam(name="version")
			TokenAndListParam theVersion,

			@RawParam
			Map<String, List<String>> theAdditionalRawParams,

	
			@IncludeParam
			Set<Include> theIncludes,

			@IncludeParam(reverse=true)
			Set<Include> theRevIncludes,

			@Sort
			SortSpec theSort,
			
			@ca.uhn.fhir.rest.annotation.Count
			Integer theCount,

			@ca.uhn.fhir.rest.annotation.Offset
			Integer theOffset,

			SummaryEnum theSummaryMode,

			SearchTotalModeEnum theSearchTotalMode,

			SearchContainedModeEnum theSearchContainedMode

			) {
		startRequest(theServletRequest);
		try {
			List<NpmPackageVersionEntity> packages = myPackageVersionDao
					.findAll(org.springframework.data.domain.Sort.by(Direction.ASC, "myPackageId", "myVersionId"));
			List<ImplementationGuide> list = new ArrayList<ImplementationGuide>();

			for (NpmPackageVersionEntity npmPackage : packages) {
				ImplementationGuide ig = new ImplementationGuide();
				ig.setId(npmPackage.getPackageId() + "-" + npmPackage.getVersionId());
				ig.setTitle(npmPackage.getDescription());
				ig.setDate(npmPackage.getUpdatedTime());
				ig.setPackageId(npmPackage.getPackageId());
				if (npmPackage.isCurrentVersion()) {
					ig.setVersion(npmPackage.getVersionId() + " (last)");
				} else {
					ig.setVersion(npmPackage.getVersionId());
				}
				list.add(ig);
			}

			SimpleBundleProvider simpleBundleProivder = new SimpleBundleProvider(list);
			return simpleBundleProivder;

		} finally {
			endRequest(theServletRequest);
		}
	}

	@Override
	public ImplementationGuide read(HttpServletRequest theServletRequest, IIdType theId,
			RequestDetails theRequestDetails) {

		startRequest(theServletRequest);
		try {
			return new TransactionTemplate(myTxManager).execute(tx -> {
				String id = theId.getIdPart().substring(0, theId.getIdPart().lastIndexOf("-"));
				String version = theId.getIdPart().substring(theId.getIdPart().lastIndexOf("-") + 1);
				Optional<NpmPackageVersionEntity> packages = myPackageVersionDao.findByPackageIdAndVersion(id, version);
				if (packages.isPresent()) {
					NpmPackageVersionEntity npmPackage = packages.get();
					ImplementationGuide ig = new ImplementationGuide();
					ig.setId(npmPackage.getPackageId() + "-" + npmPackage.getVersionId());
					ig.setTitle(npmPackage.getDescription());
					ig.setDate(npmPackage.getUpdatedTime());
					ig.setPackageId(npmPackage.getPackageId());
					ig.setVersion(npmPackage.getVersionId());
					return ig;
				}
				return null;
			});
		} finally {
			endRequest(theServletRequest);
		}
	}

}
