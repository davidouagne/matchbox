package ch.ahdis.matchbox.util;

import static org.apache.commons.lang3.StringUtils.defaultString;
import static org.apache.commons.lang3.StringUtils.isBlank;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.Validate;
import org.hl7.fhir.Uri;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseExtension;
import org.hl7.fhir.instance.model.api.IBaseHasExtensions;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.ImplementationGuide;
import org.hl7.fhir.r4.model.ImplementationGuide.ImplementationGuideDefinitionComponent;
import org.hl7.fhir.r4.model.ImplementationGuide.ImplementationGuideDefinitionResourceComponent;
import org.hl7.fhir.utilities.npm.IPackageCacheManager;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.hl7.fhir.utilities.npm.NpmPackage.NpmPackageFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.JsonElement;

import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition;
import ca.uhn.fhir.context.BaseRuntimeElementDefinition;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.dao.data.INpmPackageVersionDao;
import ca.uhn.fhir.jpa.model.config.PartitionSettings;
import ca.uhn.fhir.jpa.model.entity.NpmPackageVersionEntity;
import ca.uhn.fhir.jpa.model.util.JpaConstants;
import ca.uhn.fhir.jpa.packages.IHapiPackageCacheManager;
import ca.uhn.fhir.jpa.packages.IPackageInstallerSvc;
import ca.uhn.fhir.jpa.packages.ImplementationGuideInstallationException;
import ca.uhn.fhir.jpa.packages.JpaPackageCache;
import ca.uhn.fhir.jpa.packages.PackageDeleteOutcomeJson;
import ca.uhn.fhir.jpa.packages.PackageInstallOutcomeJson;
import ca.uhn.fhir.jpa.packages.PackageInstallationSpec;
import ca.uhn.fhir.jpa.partition.SystemRequestDetails;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.searchparam.registry.ISearchParamRegistryController;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.param.UriParam;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.util.ISearchParamRegistry;
import ca.uhn.fhir.util.FhirTerser;
import ca.uhn.fhir.util.SearchParameterUtil;

/**
 * This is a copy of ca.uhn.fhir.jpa.packages.PackageInstallerSvcImpl
 * with the following modifications:
 * - Resources with status "draft" are also loaded
 * - examples are also loaded
 * Modifications are marked in source code comments with "MODIFIED"
 * 
 * @author alexander kreutz
 *
 */
public class MatchboxPackageInstallerImpl implements IPackageInstallerSvc {

	private static final Logger ourLog = LoggerFactory.getLogger(MatchboxPackageInstallerImpl.class);
	public static List<String> DEFAULT_INSTALL_TYPES = Collections.unmodifiableList(Lists.newArrayList(
		"NamingSystem",
		"CodeSystem",
		"ValueSet",
		"StructureDefinition",
		"ConceptMap",
		"SearchParameter",
		"Subscription"
	));

	boolean enabled = true;
	@Autowired
	private FhirContext myFhirContext;
	@Autowired
	private DaoRegistry myDaoRegistry;
	@Autowired
	private IValidationSupport validationSupport;
	@Autowired
	private IHapiPackageCacheManager myPackageCacheManager;
	@Autowired
	private PlatformTransactionManager myTxManager;
	@Autowired
	private INpmPackageVersionDao myPackageVersionDao;
	@Autowired
	private ISearchParamRegistry mySearchParamRegistry;
	@Autowired
	private ISearchParamRegistryController mySearchParamRegistryController;
	@Autowired
	private PartitionSettings myPartitionSettings;
	/**
	 * Constructor
	 */
	public MatchboxPackageInstallerImpl() {
		super();
	}

	@PostConstruct
	public void initialize() {
		switch (myFhirContext.getVersion().getVersion()) {
			case R5:
			case R4:
			case DSTU3:
				break;

			case DSTU2:
			case DSTU2_HL7ORG:
			case DSTU2_1:
			default: {
				ourLog.info("IG installation not supported for version: {}", myFhirContext.getVersion().getVersion());
				enabled = false;
			}
		}
	}
	
  // MODIFIED: added
  public PackageDeleteOutcomeJson uninstall(PackageInstallationSpec theInstallationSpec) throws ImplementationGuideInstallationException {
    PackageInstallOutcomeJson retVal = new PackageInstallOutcomeJson();
    boolean exists = new TransactionTemplate(myTxManager).execute(tx -> {
      Optional<NpmPackageVersionEntity> existing = myPackageVersionDao.findByPackageIdAndVersion(theInstallationSpec.getName(), theInstallationSpec.getVersion());
      return existing.isPresent();
    });
    if (exists) {
        ourLog.info("Remove Package {}#{} because it is a package based on an external url", theInstallationSpec.getName(), theInstallationSpec.getVersion());
        return myPackageCacheManager.uninstallPackage(theInstallationSpec.getName(), theInstallationSpec.getVersion());
    }
    return null;
  }

	/**
	 * Loads and installs an IG from a file on disk or the Simplifier repo using
	 * the {@link IPackageCacheManager}.
	 * <p>
	 * Installs the IG by persisting instances of the following types of resources:
	 * <p>
	 * - NamingSystem, CodeSystem, ValueSet, StructureDefinition (with snapshots),
	 * ConceptMap, SearchParameter, Subscription
	 * <p>
	 * Creates the resources if non-existent, updates them otherwise.
	 *
	 * @param theInstallationSpec The details about what should be installed
	 */
	@SuppressWarnings("ConstantConditions")
	public PackageInstallOutcomeJson install(PackageInstallationSpec theInstallationSpec) throws ImplementationGuideInstallationException {
		PackageInstallOutcomeJson retVal = new PackageInstallOutcomeJson();
		if (enabled) {
			try {

				boolean exists = new TransactionTemplate(myTxManager).execute(tx -> {
					Optional<NpmPackageVersionEntity> existing = myPackageVersionDao.findByPackageIdAndVersion(theInstallationSpec.getName(), theInstallationSpec.getVersion());
					return existing.isPresent();
				});
				if (exists) {
					ourLog.info("Package {}#{} is already installed", theInstallationSpec.getName(), theInstallationSpec.getVersion());
				  // MODIFIED: This has been added to add remove packages based on url
					if (theInstallationSpec.getPackageUrl()!=null) {
					  ourLog.info("Remove Package {}#{} because it is a package based on an external url", theInstallationSpec.getName(), theInstallationSpec.getVersion());
					  myPackageCacheManager.uninstallPackage(theInstallationSpec.getName(), theInstallationSpec.getVersion());
					}
				}

				NpmPackage npmPackage = myPackageCacheManager.installPackage(theInstallationSpec);
				if (npmPackage == null) {
					throw new IOException("Package not found");
				}

				retVal.getMessage().addAll(JpaPackageCache.getProcessingMessages(npmPackage));

// FIXME no dependencies				if (theInstallationSpec.isFetchDependencies()) {
//					fetchAndInstallDependencies(npmPackage, theInstallationSpec, retVal);
//				}

				if (theInstallationSpec.getInstallMode() == PackageInstallationSpec.InstallModeEnum.STORE_AND_INSTALL) {
					install(npmPackage, theInstallationSpec, retVal);

					// If any SearchParameters were installed, let's load them right away
					mySearchParamRegistryController.refreshCacheIfNecessary();
				}

			} catch (IOException e) {
				throw new ImplementationGuideInstallationException("Could not load NPM package " + theInstallationSpec.getName() + "#" + theInstallationSpec.getVersion(), e);
			}
		}

		return retVal;
	}
	
	 private void addExtensionPackageUrl(IBaseResource newResource, String packageUrl ) {
	    if (newResource instanceof IBaseHasExtensions) {
	      IBaseExtension<?, ?> extension = ((IBaseHasExtensions) newResource).addExtension();
	      extension.setUrl("http://ahdis.ch/fhir/extension/packageUrl");
	      IPrimitiveType<Uri> retval = (IPrimitiveType<Uri>) myFhirContext.getElementDefinition("uri").newInstance();
	      retval.setValueAsString(packageUrl);
	      extension.setValue(retval);
	    }
	  }
	 // FIXME should maybe work independent of model
   // Resource Bundle/history-IHE-formatcode.valueset not found, specified in path: ImplementationGuide.definition.resource.reference
	 private IBaseResource filterImplementationGuideResources(IBaseResource newResource, List<String> installTypes ) {
	   ImplementationGuide ig = (ImplementationGuide) newResource;
	   ImplementationGuideDefinitionComponent definition = ig.getDefinition();
	   if (definition!=null) {
	     List<ImplementationGuideDefinitionResourceComponent> resourceList = new ArrayList<ImplementationGuideDefinitionResourceComponent>(definition.getResource());
	     for (ImplementationGuideDefinitionResourceComponent resource : resourceList) {
	       if (resource.getReference()!=null && resource.getReference().getReference()!=null) {
	         String reference = resource.getReference().getReference();
	         int pos = reference.indexOf("/");
	         if (pos>0) {
	           String res = reference.substring(0,pos-1);
	           if (installTypes.indexOf(res)==-1) {
	             definition.getResource().remove(resource);
	           }
	         }
	        
	       }
	     }
	   }
	   
	   return newResource;
	 }

	/**
	 * Installs a package and its dependencies.
	 * <p>
	 * Fails fast if one of its dependencies could not be installed.
	 *
	 * @throws ImplementationGuideInstallationException if installation fails
	 */
	private void install(NpmPackage npmPackage, PackageInstallationSpec theInstallationSpec, PackageInstallOutcomeJson theOutcome) throws ImplementationGuideInstallationException {
		String name = npmPackage.getNpm().get("name").getAsString();
		String version = npmPackage.getNpm().get("version").getAsString();

		String fhirVersion = npmPackage.fhirVersion();
		String currentFhirVersion = myFhirContext.getVersion().getVersion().getFhirVersionString();
		assertFhirVersionsAreCompatible(fhirVersion, currentFhirVersion);

		List<String> installTypes;
		if (!theInstallationSpec.getInstallResourceTypes().isEmpty()) {
			installTypes = theInstallationSpec.getInstallResourceTypes();
		} else {
			installTypes = DEFAULT_INSTALL_TYPES;
		}

		ourLog.info("Installing package: {}#{}", name, version);
		int[] count = new int[installTypes.size()];
		
		IBaseResource ig = null;

		for (int i = 0; i < installTypes.size(); i++) {
			Collection<IBaseResource> resources = parseResourcesOfType(installTypes.get(i), npmPackage);
			count[i] = resources.size();

			for (IBaseResource next : resources) {
				try {
			    next = isStructureDefinitionWithoutSnapshot(next) ? generateSnapshot(next) : next;
			    // add package url as an extension if provided
			    if (this.isImplementationGuide(next)) {
			      ig = next;
			      if (theInstallationSpec.getPackageUrl()!=null) {
			        addExtensionPackageUrl(ig, theInstallationSpec.getPackageUrl());
			      }
			      filterImplementationGuideResources(ig, installTypes);
			    }
					create(next, theOutcome);
				} catch (Exception e) {
					ourLog.debug("Failed to upload resource of type {} with ID {} - Error: {}", myFhirContext.getResourceType(next), next.getIdElement().getValue(), e.toString());
					throw new ImplementationGuideInstallationException(String.format("Error installing IG %s#%s: %s", name, version, e.toString()), e);
				}
			}
		}
		

    // Modified add log
    String log = String.format("Finished installation of package %s#%s:", name, version);
    ourLog.info(log);
    theOutcome.getMessage().add(log);

		for (int i = 0; i < count.length; i++) {
		  // Modified add log
		  log = String.format("-- Created or updated %s resources of type %s", count[i], installTypes.get(i));
			ourLog.info(log);
			theOutcome.getMessage().add(log);
		}
		
		if (ig == null) {
      log = String.format("No Implementaiton Guide provided for package %s#%s:", name, version);
      ourLog.info(log);
      theOutcome.getMessage().add(log);
		}

	}

	private void fetchAndInstallDependencies(NpmPackage npmPackage, PackageInstallationSpec theInstallationSpec, PackageInstallOutcomeJson theOutcome) throws ImplementationGuideInstallationException {
		if (npmPackage.getNpm().has("dependencies")) {
			JsonElement dependenciesElement = npmPackage.getNpm().get("dependencies");
			Map<String, String> dependencies = new Gson().fromJson(dependenciesElement, HashMap.class);
			for (Map.Entry<String, String> d : dependencies.entrySet()) {
				String id = d.getKey();
				String ver = d.getValue();
				try {
					theOutcome.getMessage().add("Package " + npmPackage.id() + "#" + npmPackage.version() + " depends on package " + id + "#" + ver);

					boolean skip = false;
					for (String next : theInstallationSpec.getDependencyExcludes()) {
						if (id.matches(next)) {
							theOutcome.getMessage().add("Not installing dependency " + id + " because it matches exclude criteria: " + next);
							skip = true;
							break;
						}
					}
					if (skip) {
						continue;
					}

					// resolve in local cache or on packages.fhir.org
					NpmPackage dependency = myPackageCacheManager.loadPackage(id, ver);
					// recursive call to install dependencies of a package before
					// installing the package
					fetchAndInstallDependencies(dependency, theInstallationSpec, theOutcome);

					if (theInstallationSpec.getInstallMode() == PackageInstallationSpec.InstallModeEnum.STORE_AND_INSTALL) {
						install(dependency, theInstallationSpec, theOutcome);
					}

				} catch (IOException e) {
					throw new ImplementationGuideInstallationException(String.format(
						"Cannot resolve dependency %s#%s", id, ver), e);
				}
			}
		}
	}

	/**
	 * Asserts if package FHIR version is compatible with current FHIR version
	 * by using semantic versioning rules.
	 */
	private void assertFhirVersionsAreCompatible(String fhirVersion, String currentFhirVersion)
		throws ImplementationGuideInstallationException {

		FhirVersionEnum fhirVersionEnum = FhirVersionEnum.forVersionString(fhirVersion);
		FhirVersionEnum currentFhirVersionEnum = FhirVersionEnum.forVersionString(currentFhirVersion);
		Validate.notNull(fhirVersionEnum, "Invalid FHIR version string: %s", fhirVersion);
		Validate.notNull(currentFhirVersionEnum, "Invalid FHIR version string: %s", currentFhirVersion);
		boolean compatible = fhirVersionEnum.equals(currentFhirVersionEnum);
		if (!compatible) {
			throw new ImplementationGuideInstallationException(String.format(
				"Cannot install implementation guide: FHIR versions mismatch (expected <=%s, package uses %s)",
				currentFhirVersion, fhirVersion));
		}
	}

	/**
	 * ============================= Utility methods ===============================
	 */

	// MODIFIED: This method has been reimplemented: also add example folder 
	private List<IBaseResource> parseResourcesOfType(String type, NpmPackage pkg) {
		if (!pkg.getFolders().containsKey("package")) {
			return Collections.emptyList();
		}
					
		ArrayList<IBaseResource> resources = new ArrayList<>();
		
		addFolder(type, pkg.getFolders().get("package"), resources);
		
		NpmPackageFolder exampleFolder = pkg.getFolders().get("example");
		if (exampleFolder != null) {			
			try {
			  pkg.indexFolder("example", exampleFolder);			  
			  addFolder(type, exampleFolder, resources);
			} catch (IOException e) {
				throw new InternalErrorException("Cannot install resource of type " + type + ": Could not read example directory", e);
			}
		}
		
				
		return resources;
				
	}
	
	// MODIFIED: This utility method has been added. It is used by parseResourcesOfType(type, pkg)
	private void addFolder(String type, NpmPackageFolder folder, List<IBaseResource> resources) {
		if (folder == null) return;		
		List<String> filesForType = folder.getTypes().get(type);
		if (filesForType == null) return;
		for (String file : filesForType) {
			try {
				byte[] content = folder.fetchFile(file);
				resources.add(myFhirContext.newJsonParser().parseResource(new String(content)));
			} catch (IOException e) {
				throw new InternalErrorException("Cannot install resource of type " + type + ": Could not fetch file " + file, e);
			}
		}
	}

	public void create(IBaseResource theResource, PackageInstallOutcomeJson theOutcome) {
		IFhirResourceDao dao = myDaoRegistry.getResourceDao(theResource.getClass());
		SearchParameterMap map = createSearchParameterMapFor(theResource);
		IBundleProvider searchResult = searchResource(dao, map);
		if (validForUpload(theResource)) {
			if (searchResult.isEmpty()) {

				ourLog.debug("Creating new resource matching {}", map.toNormalizedQueryString(myFhirContext));
				theOutcome.incrementResourcesInstalled(myFhirContext.getResourceType(theResource));

				IIdType id = theResource.getIdElement();

				if (id.isEmpty()) {
					createResource(dao, theResource);
					ourLog.debug("Created resource with new id");
				} else {
					if (id.isIdPartValidLong()) {
						String newIdPart = "npm-" + id.getIdPart();
						id.setParts(id.getBaseUrl(), id.getResourceType(), newIdPart, id.getVersionIdPart());
					}
					updateResource(dao, theResource);
					ourLog.info("Created resource with existing id "+id.toString());
				}
			} else {
			ourLog.debug("Updating existing resource matching {}", map.toNormalizedQueryString(myFhirContext));
				theResource.setId(searchResult.getResources(0, 1).get(0).getIdElement().toUnqualifiedVersionless());
				DaoMethodOutcome outcome = updateResource(dao, theResource);
				if (!outcome.isNop()) {
					theOutcome.incrementResourcesInstalled(myFhirContext.getResourceType(theResource));
				}
			}

		}
	}

	private IBundleProvider searchResource(IFhirResourceDao theDao, SearchParameterMap theMap) {
		if (myPartitionSettings.isPartitioningEnabled()) {
			SystemRequestDetails requestDetails = new SystemRequestDetails();
//			requestDetails.setTenantId(JpaConstants.DEFAULT_PARTITION_NAME);
			return theDao.search(theMap, requestDetails);
		} else {
			return theDao.search(theMap);
		}
	}

	private void createResource(IFhirResourceDao theDao, IBaseResource theResource) {
		if (myPartitionSettings.isPartitioningEnabled()) {
			SystemRequestDetails requestDetails = new SystemRequestDetails();
			requestDetails.setTenantId(JpaConstants.DEFAULT_PARTITION_NAME);
			theDao.create(theResource, requestDetails);
		} else {
			theDao.create(theResource);
		}
	}

	private DaoMethodOutcome updateResource(IFhirResourceDao theDao, IBaseResource theResource) {
		if (myPartitionSettings.isPartitioningEnabled()) {
			SystemRequestDetails requestDetails = new SystemRequestDetails();
			requestDetails.setTenantId(JpaConstants.DEFAULT_PARTITION_NAME);
			return theDao.update(theResource, requestDetails);
		} else {
			return theDao.update(theResource);
		}
	}

	// MODIFIED: This method has been modified: Also allow Resources with status "draft"
	boolean validForUpload(IBaseResource theResource) {
		String resourceType = myFhirContext.getResourceType(theResource);
		if ("SearchParameter".equals(resourceType)) {

			String code = SearchParameterUtil.getCode(myFhirContext, theResource);
			if (defaultString(code).startsWith("_")) {
				return false;
			}

			String expression = SearchParameterUtil.getExpression(myFhirContext, theResource);
			if (isBlank(expression)) {
				return false;
			}

			if (SearchParameterUtil.getBaseAsStrings(myFhirContext, theResource).isEmpty()) {
				return false;
			}
		}

		List<IPrimitiveType> statusTypes = myFhirContext.newFhirPath().evaluate(theResource, "status", IPrimitiveType.class);
		if (statusTypes.size() > 0) {
			// Modified condition
			if (!statusTypes.get(0).getValueAsString().equals("active") && !statusTypes.get(0).getValueAsString().equals("draft")) {
				return false;
			}
		}

		return true;
	}

	private boolean isStructureDefinitionWithoutSnapshot(IBaseResource r) {
		boolean retVal = false;
		FhirTerser terser = myFhirContext.newTerser();
		if (r.getClass().getSimpleName().equals("StructureDefinition")) {
			Optional<String> kind = terser.getSinglePrimitiveValue(r, "kind");
			if (kind.isPresent() && !(kind.get().equals("logical"))) {
				retVal = terser.getSingleValueOrNull(r, "snapshot") == null;
			}
		}
		return retVal;
	}
	
	private boolean isImplementationGuide(IBaseResource  r) { 
    if (r.getClass().getSimpleName().equals("ImplementationGuide")) {
      return true;
    }
    return false;
  }

	private IBaseResource generateSnapshot(IBaseResource sd) {
		try {
			return validationSupport.generateSnapshot(new ValidationSupportContext(validationSupport), sd, null, null, null);
		} catch (Exception e) {
			throw new ImplementationGuideInstallationException(String.format(
				"Failure when generating snapshot of StructureDefinition: %s", sd.getIdElement()), e);
		}
	}

	private SearchParameterMap createSearchParameterMapFor(IBaseResource resource) {
		if (resource.getClass().getSimpleName().equals("NamingSystem")) {
			String uniqueId = extractUniqeIdFromNamingSystem(resource);
			return SearchParameterMap.newSynchronous().add("value", new StringParam(uniqueId).setExact(true));
		} else if (resource.getClass().getSimpleName().equals("Subscription")) {
			String id = extractIdFromSubscription(resource);
			return SearchParameterMap.newSynchronous().add("_id", new TokenParam(id));
		} else if (resourceHasUrlElement(resource)) {
			String url = extractUniqueUrlFromMetadataResource(resource);
			return SearchParameterMap.newSynchronous().add("url", new UriParam(url));
		} else {
			TokenParam identifierToken = extractIdentifierFromOtherResourceTypes(resource);
			return SearchParameterMap.newSynchronous().add("identifier", identifierToken);
		}
	}

	private String extractUniqeIdFromNamingSystem(IBaseResource resource) {
		FhirTerser terser = myFhirContext.newTerser();
		IBase uniqueIdComponent = (IBase) terser.getSingleValueOrNull(resource, "uniqueId");
		if (uniqueIdComponent == null) {
			throw new ImplementationGuideInstallationException("NamingSystem does not have uniqueId component.");
		}
		IPrimitiveType<?> asPrimitiveType = (IPrimitiveType<?>) terser.getSingleValueOrNull(uniqueIdComponent, "value");
		return (String) asPrimitiveType.getValue();
	}

	private String extractIdFromSubscription(IBaseResource resource) {
		FhirTerser terser = myFhirContext.newTerser();
		IPrimitiveType<?> asPrimitiveType = (IPrimitiveType<?>) terser.getSingleValueOrNull(resource, "id");
		return (String) asPrimitiveType.getValue();
	}

	private String extractUniqueUrlFromMetadataResource(IBaseResource resource) {
		FhirTerser terser = myFhirContext.newTerser();
		IPrimitiveType<?> asPrimitiveType = (IPrimitiveType<?>) terser.getSingleValueOrNull(resource, "url");
		return (String) asPrimitiveType.getValue();
	}

	private TokenParam extractIdentifierFromOtherResourceTypes(IBaseResource resource) {
		FhirTerser terser = myFhirContext.newTerser();
		Identifier identifier = (Identifier) terser.getSingleValueOrNull(resource, "identifier");
		if (identifier != null) {
			return new TokenParam(identifier.getSystem(), identifier.getValue());
		} else {
			throw new UnsupportedOperationException("Resources in a package must have a url or identifier to be loaded by the package installer.");
		}
	}

	private boolean resourceHasUrlElement(IBaseResource resource) {
		BaseRuntimeElementDefinition<?> def = myFhirContext.getElementDefinition(resource.getClass());
		if (!(def instanceof BaseRuntimeElementCompositeDefinition)) {
			throw new IllegalArgumentException("Resource is not a composite type: " + resource.getClass().getName());
		}
		BaseRuntimeElementCompositeDefinition<?> currentDef = (BaseRuntimeElementCompositeDefinition<?>) def;
		BaseRuntimeChildDefinition nextDef = currentDef.getChildByName("url");
		return nextDef != null;
	}

	@VisibleForTesting
	void setFhirContextForUnitTest(FhirContext theCtx) {
		myFhirContext = theCtx;
	}

}
